package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertEquals

class ReleaseNotesMarkdownTest {
    @Test
    fun githubReleaseBodyBreaksIntoHeadingsListsAndParagraphs() {
        val notes = """
            <!-- Release template -->
            ## What's Changed
            * Fix the thing by @someone in https://github.com/o/r/pull/12
            * Another fix
              that wraps onto a second line
            1. First step
            2. Second step

            **Full Changelog**: https://github.com/o/r/compare/v1...v2
        """.trimIndent()

        val blocks = parseReleaseNotes(notes)

        assertEquals(
            listOf(
                ReleaseNotesBlock.Heading(2, "What's Changed"),
                ReleaseNotesBlock.ListItem(null, 0, "Fix the thing by @someone in https://github.com/o/r/pull/12"),
                ReleaseNotesBlock.ListItem(null, 0, "Another fix that wraps onto a second line"),
                ReleaseNotesBlock.ListItem(1, 0, "First step"),
                ReleaseNotesBlock.ListItem(2, 0, "Second step"),
                ReleaseNotesBlock.Break,
                ReleaseNotesBlock.Paragraph("**Full Changelog**: https://github.com/o/r/compare/v1...v2"),
            ),
            blocks,
        )
    }

    @Test
    fun fencedCodeAndNestedBulletsSurvive() {
        val notes = "Intro line\n```\nmpv --version\n```\n- top\n  - nested\n---\nAfter rule"

        val blocks = parseReleaseNotes(notes)

        assertEquals(
            listOf(
                ReleaseNotesBlock.Paragraph("Intro line"),
                ReleaseNotesBlock.Code("mpv --version"),
                ReleaseNotesBlock.ListItem(null, 0, "top"),
                ReleaseNotesBlock.ListItem(null, 1, "nested"),
                ReleaseNotesBlock.Break,
                ReleaseNotesBlock.Paragraph("After rule"),
            ),
            blocks,
        )
    }

    @Test
    fun orderedListsCountFromTheirFirstItemAndRestartAfterAParagraph() {
        val notes = """
            10. Ten
            11. Eleven

            **14th Sept 7am**
            1. Changing the icon
            12. New icon
            1. Third
               - nested
            1. Fourth
        """.trimIndent()

        val ordinals = parseReleaseNotes(notes)
            .filterIsInstance<ReleaseNotesBlock.ListItem>()
            .map { it.ordinal to it.text }

        assertEquals(
            listOf(
                10 to "Ten",
                11 to "Eleven",
                1 to "Changing the icon",
                2 to "New icon",
                3 to "Third",
                null to "nested",
                4 to "Fourth",
            ),
            ordinals,
        )
    }

    @Test
    fun blankLinesAreOneBreakAndNeverAtTheEdges() {
        val blocks = parseReleaseNotes("\n\nA\n\n\n\nB\n\n")

        assertEquals(
            listOf(
                ReleaseNotesBlock.Paragraph("A"),
                ReleaseNotesBlock.Break,
                ReleaseNotesBlock.Paragraph("B"),
            ),
            blocks,
        )
    }

    @Test
    fun inlineMarkupBecomesStyledRuns() {
        val runs = parseInlineMarkdown("**Bold** and *italic* with `code` and [a link](https://x.y/z) then https://a.b/c.")

        assertEquals(
            listOf(
                InlineRun("Bold", bold = true),
                InlineRun(" and "),
                InlineRun("italic", italic = true),
                InlineRun(" with "),
                InlineRun("code", code = true),
                InlineRun(" and "),
                InlineRun("a link", url = "https://x.y/z"),
                InlineRun(" then "),
                InlineRun("https://a.b/c", url = "https://a.b/c"),
                InlineRun("."),
            ),
            runs,
        )
    }

    @Test
    fun underscoresInsideWordsAndSpacedAsterisksAreNotEmphasis() {
        assertEquals(
            listOf(InlineRun("snake_case_name is 2 * 3")),
            parseInlineMarkdown("snake_case_name is 2 * 3"),
        )
    }
}
