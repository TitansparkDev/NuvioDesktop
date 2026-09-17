package com.nuvio.app.features.updater

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio

/**
 * The subset of GitHub-flavoured Markdown that release notes are actually written in.
 *
 * Release bodies come from the GitHub API as raw Markdown, and shown verbatim they read as
 * `## What's Changed` and `* Fix by @user in https://...` — the markup is louder than the words.
 * This is not a Markdown engine: headings, bullet and numbered lists, fenced code, paragraphs, and
 * the inline forms (bold, italic, code, links, bare URLs) cover everything the changelog uses, and
 * anything outside that still comes through as text rather than being dropped.
 */
internal sealed interface ReleaseNotesBlock {
    data class Heading(val level: Int, val text: String) : ReleaseNotesBlock
    data class Paragraph(val text: String) : ReleaseNotesBlock
    data class ListItem(val ordinal: Int?, val indent: Int, val text: String) : ReleaseNotesBlock
    data class Code(val text: String) : ReleaseNotesBlock

    /**
     * A blank line (or a rule) between two blocks. Kept rather than collapsed because the author
     * put it there to separate sections, and a list that runs straight into the next date heading
     * reads as one undifferentiated column.
     */
    data object Break : ReleaseNotesBlock
}

internal fun parseReleaseNotes(markdown: String): List<ReleaseNotesBlock> {
    val blocks = mutableListOf<ReleaseNotesBlock>()
    val paragraph = StringBuilder()
    fun flushParagraph() {
        if (paragraph.isNotBlank()) blocks += ReleaseNotesBlock.Paragraph(paragraph.toString().trim())
        paragraph.clear()
    }
    // One Break at most between two blocks, and none before the first: runs of blank lines are
    // one separation, and a Break at either end would be padding nothing.
    fun markBreak() {
        flushParagraph()
        if (blocks.isNotEmpty() && blocks.last() != ReleaseNotesBlock.Break) blocks += ReleaseNotesBlock.Break
    }

    // HTML comments are GitHub's release-template scaffolding, never something to show.
    val lines = markdown.replace(HTML_COMMENT, "").lines()
    var index = 0
    while (index < lines.size) {
        val raw = lines[index]
        val line = raw.trimEnd()
        when {
            line.trimStart().startsWith("```") -> {
                flushParagraph()
                val code = mutableListOf<String>()
                index += 1
                while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                    code += lines[index]
                    index += 1
                }
                blocks += ReleaseNotesBlock.Code(code.joinToString("\n"))
            }
            line.isBlank() -> markBreak()
            HEADING.matches(line) -> {
                flushParagraph()
                val match = HEADING.find(line)!!
                blocks += ReleaseNotesBlock.Heading(match.groupValues[1].length, match.groupValues[2].trim())
            }
            BULLET.matches(line) -> {
                flushParagraph()
                val match = BULLET.find(line)!!
                blocks += ReleaseNotesBlock.ListItem(
                    ordinal = null,
                    indent = match.groupValues[1].length / 2,
                    text = match.groupValues[2].trim(),
                )
            }
            NUMBERED.matches(line) -> {
                flushParagraph()
                val match = NUMBERED.find(line)!!
                blocks += ReleaseNotesBlock.ListItem(
                    ordinal = match.groupValues[2].toIntOrNull(),
                    indent = match.groupValues[1].length / 2,
                    text = match.groupValues[3].trim(),
                )
            }
            HORIZONTAL_RULE.matches(line) -> markBreak()
            else -> {
                // A line continuing a list item is indented under it; fold it into that item.
                val previous = blocks.lastOrNull()
                if (paragraph.isEmpty() && previous is ReleaseNotesBlock.ListItem && raw.startsWith("  ")) {
                    blocks[blocks.lastIndex] = previous.copy(text = previous.text + " " + line.trim())
                } else {
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(line.trim())
                }
            }
        }
        index += 1
    }
    flushParagraph()
    if (blocks.lastOrNull() == ReleaseNotesBlock.Break) blocks.removeAt(blocks.lastIndex)
    return renumberOrderedLists(blocks)
}

/**
 * Numbers ordered items the way GitHub does: a list counts up from its first item's number, and
 * whatever the author typed on later items is ignored. Authors lean on this — a changelog kept by
 * hand has "1." on every line, or numbers that carried on from the previous date's list — and the
 * source numbers shown raw read as 11, 1, 12. A list is broken by any block that is not one of its
 * items; blank lines and nested items keep it going, as in CommonMark.
 */
private fun renumberOrderedLists(blocks: List<ReleaseNotesBlock>): List<ReleaseNotesBlock> {
    val result = blocks.toMutableList()
    for (index in result.indices) {
        val block = result[index] as? ReleaseNotesBlock.ListItem ?: continue
        if (block.ordinal == null) continue
        val previous = (index - 1 downTo 0)
            .asSequence()
            .map { result[it] }
            // Blank lines and deeper items are part of this list; anything else ends it.
            .takeWhile { it == ReleaseNotesBlock.Break || it is ReleaseNotesBlock.ListItem && it.indent >= block.indent }
            .filterIsInstance<ReleaseNotesBlock.ListItem>()
            .firstOrNull { it.indent == block.indent }
        if (previous?.ordinal != null) result[index] = block.copy(ordinal = previous.ordinal + 1)
    }
    return result
}

/**
 * One inline run: text with the styles that apply to it, and the URL it links to, if any.
 * Produced by [parseInlineMarkdown] so the renderer only has to lay spans down.
 */
internal data class InlineRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val url: String? = null,
)

internal fun parseInlineMarkdown(text: String): List<InlineRun> {
    val runs = mutableListOf<InlineRun>()
    var bold = false
    var italic = false
    val plain = StringBuilder()
    fun flushPlain() {
        if (plain.isNotEmpty()) runs += InlineRun(plain.toString(), bold = bold, italic = italic)
        plain.clear()
    }

    var index = 0
    while (index < text.length) {
        val rest = text.substring(index)
        when {
            rest.startsWith("`") -> {
                val end = text.indexOf('`', index + 1)
                if (end > index) {
                    flushPlain()
                    runs += InlineRun(text.substring(index + 1, end), code = true)
                    index = end + 1
                } else {
                    plain.append('`')
                    index += 1
                }
            }
            rest.startsWith("**") || rest.startsWith("__") -> {
                flushPlain()
                bold = !bold
                index += 2
            }
            (rest.startsWith("*") || rest.startsWith("_")) && emphasisDelimits(text, index) -> {
                flushPlain()
                italic = !italic
                index += 1
            }
            rest.startsWith("[") -> {
                val link = LINK.find(rest)
                if (link != null && link.range.first == 0) {
                    flushPlain()
                    runs += InlineRun(link.groupValues[1], bold = bold, italic = italic, url = link.groupValues[2])
                    index += link.value.length
                } else {
                    plain.append('[')
                    index += 1
                }
            }
            rest.startsWith("http://") || rest.startsWith("https://") -> {
                // Sentence punctuation after a URL belongs to the sentence, not the link.
                val url = BARE_URL.find(rest)!!.value.trimEnd('.', ',', ';', ':', '!', '?')
                flushPlain()
                runs += InlineRun(url, bold = bold, italic = italic, url = url)
                index += url.length
            }
            else -> {
                plain.append(text[index])
                index += 1
            }
        }
    }
    flushPlain()
    return runs
}

/**
 * Whether a lone `*` or `_` at [index] opens or closes emphasis rather than being a character —
 * `snake_case_names` and `2 * 3` must survive. An opener has a non-space after it, a closer a
 * non-space before it; an underscore inside a word is never either.
 */
private fun emphasisDelimits(text: String, index: Int): Boolean {
    val marker = text[index]
    val before = text.getOrNull(index - 1)
    val after = text.getOrNull(index + 1)
    if (marker == '_' && before?.isLetterOrDigit() == true && after?.isLetterOrDigit() == true) return false
    val opens = after != null && !after.isWhitespace()
    val closes = before != null && !before.isWhitespace()
    return opens || closes
}

private val HTML_COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
private val HEADING = Regex("""^\s{0,3}(#{1,6})\s+(.*?)\s*#*$""")
private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val NUMBERED = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")
private val HORIZONTAL_RULE = Regex("""^\s{0,3}([-*_])(\s*\1){2,}\s*$""")
private val LINK = Regex("""\[([^\]]+)]\(([^)\s]+)(?:\s+"[^"]*")?\)""")
private val BARE_URL = Regex("""https?://[^\s<>)\]]+""")

/** Release notes laid out as the Markdown intends, in the dialog's own type and colours. */
@Composable
internal fun ReleaseNotesMarkdown(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val blocks = remember(markdown) { parseReleaseNotes(markdown) }
    val body = MaterialTheme.typography.bodyMedium
    val linkStyles = TextLinkStyles(
        style = SpanStyle(color = tokens.colors.accent, textDecoration = TextDecoration.Underline),
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is ReleaseNotesBlock.Heading -> Text(
                    text = inlineAnnotated(block.text, linkStyles),
                    style = if (block.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    // Breathing room above a heading that follows other content, none at the top
                    // and none when a blank line already made the gap.
                    modifier = Modifier.padding(
                        top = if (index == 0 || blocks[index - 1] == ReleaseNotesBlock.Break) 0.dp else 6.dp,
                    ),
                )
                is ReleaseNotesBlock.Paragraph -> Text(
                    text = inlineAnnotated(block.text, linkStyles),
                    style = body,
                    color = tokens.colors.textPrimary,
                )
                is ReleaseNotesBlock.ListItem -> Row(
                    modifier = Modifier.fillMaxWidth().padding(start = (block.indent * 16).dp),
                ) {
                    Text(
                        text = block.ordinal?.let { "$it." } ?: "•",
                        style = body,
                        color = tokens.colors.textMuted,
                        modifier = Modifier.width(20.dp),
                    )
                    Text(
                        text = inlineAnnotated(block.text, linkStyles),
                        style = body,
                        color = tokens.colors.textPrimary,
                    )
                }
                is ReleaseNotesBlock.Code -> Text(
                    text = block.text,
                    style = body.copy(fontFamily = FontFamily.Monospace),
                    color = tokens.colors.textMuted,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
                // On top of the column's own 6dp, so a blank line reads as a paragraph gap.
                ReleaseNotesBlock.Break -> Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}

private fun inlineAnnotated(text: String, linkStyles: TextLinkStyles): AnnotatedString =
    buildAnnotatedString {
        parseInlineMarkdown(text).forEach { run ->
            val style = SpanStyle(
                fontWeight = if (run.bold) FontWeight.SemiBold else null,
                fontStyle = if (run.italic) FontStyle.Italic else null,
                fontFamily = if (run.code) FontFamily.Monospace else null,
            )
            if (run.url != null) {
                withLink(LinkAnnotation.Url(run.url, linkStyles)) { withStyle(style) { append(run.text) } }
            } else {
                withStyle(style) { append(run.text) }
            }
        }
    }
