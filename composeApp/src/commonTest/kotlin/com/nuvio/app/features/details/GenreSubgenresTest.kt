package com.nuvio.app.features.details

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenreSubgenresTest {

    // Midsommar as IMDb files it: interests with their parent genre. IMDb lists the plain genres
    // among them too, which the parser drops before they reach here.
    private val midsommarInterests = listOf(
        ImdbInterest("Dark Comedy", "Comedy"),
        ImdbInterest("Folk Horror", "Horror"),
        ImdbInterest("Psychological Drama", "Drama"),
        ImdbInterest("Psychological Horror", "Horror"),
        ImdbInterest("Psychological Thriller", "Thriller"),
    )
    private val midsommarKeywords = listOf("gore", "cult", "isolation", "dark", "grief", "suicide", "secret")
    private val midsommarGenres = listOf("Horror", "Drama", "Mystery")

    @Test
    fun cardIsOneListForTheTitleSubgenresFirstThenThemes() {
        val card = GenreSubgenres.card(midsommarGenres, midsommarInterests, midsommarKeywords)
        assertEquals(
            listOf("Dark Comedy", "Folk Horror", "Psychological Drama", "Psychological Horror", "Psychological Thriller"),
            card.subgenres,
        )
        // Horror's themes, then Drama's, then Mystery's ("Cult" already said by Horror).
        assertEquals(listOf("Gore", "Cult", "Isolation", "Dark", "Grief", "Suicide", "Secret"), card.themes)
        assertEquals("Dark Comedy · Folk Horror · Psychological Drama · Psychological Horror · Psychological Thriller", card.headline)
        assertEquals("Gore · Cult · Isolation · Dark · Grief · Suicide · Secret", card.themeLine)
    }

    @Test
    fun aThemeAlreadyNamedAsASubgenreIsNotRepeated() {
        // South Park: "sitcom" and "satire" are both TMDB keywords and IMDb interests.
        val card = GenreSubgenres.card(
            genres = listOf("Animation", "Comedy"),
            interests = listOf(
                ImdbInterest("Adult Animation", "Animation"),
                ImdbInterest("Sitcom", "Comedy"),
                ImdbInterest("Satire", "Comedy"),
                ImdbInterest("Sitcom", "Comedy"),
            ),
            keywords = listOf("sitcom", "satire", "parody", "small town"),
        )
        assertEquals(listOf("Adult Animation", "Sitcom", "Satire"), card.subgenres)
        assertEquals(listOf("Parody"), card.themes)
    }

    @Test
    fun themesAreCappedAcrossGenres() {
        val keywords = listOf(
            "slasher", "gore", "zombie", "vampire", "witch", "curse", // six for Horror
            "heist", "spy", "revenge", "hostage", // more for Thriller
        )
        val card = GenreSubgenres.card(listOf("Horror", "Thriller"), emptyList(), keywords)
        assertEquals(GenreSubgenres.MAX_THEMES, card.themes.size)
        assertEquals("Slasher", card.themes.first())
        assertEquals("Spy", card.themes.last())
    }

    @Test
    fun cardIsEmptyWhenNothingRefinesTheTitle() {
        val card = GenreSubgenres.card(listOf("Western"), emptyList(), listOf("grief"))
        assertTrue(card.isEmpty)
        assertEquals("", card.headline)
        assertEquals("", card.themeLine)
    }

    @Test
    fun anInterestRepeatingOneOfTheGenresIsNotASubgenre() {
        val card = GenreSubgenres.card(
            listOf("Sci-Fi & Fantasy"),
            listOf(ImdbInterest("Science Fiction", "Sci-Fi"), ImdbInterest("Space Sci-Fi", "Sci-Fi"), ImdbInterest("Fantasy", "Fantasy")),
            emptyList(),
        )
        assertEquals(listOf("Space Sci-Fi"), card.subgenres)
    }

    // Shutter Island's TMDB tags, as MDBList relays them.
    private val shutterIsland = listOf(
        "island", "u.s. marshal", "psychiatric hospital", "mental illness", "asylum",
        "psychological thriller", "neo-noir", "1950s", "based on novel or book", "delusion",
    )

    @Test
    fun thrillerBreaksDownIntoItsSubgenresInVocabularyOrder() {
        assertEquals(
            listOf("Psychological thriller", "Neo-noir", "Asylum", "Psychiatric hospital", "Mental illness"),
            GenreSubgenres.breakdown("Thriller", shutterIsland),
        )
    }

    @Test
    fun eachGenreReadsTheSameTagsDifferently() {
        assertEquals(
            listOf("Neo-noir", "Mental illness", "Asylum", "Psychiatric hospital", "Island"),
            GenreSubgenres.breakdown("Mystery", shutterIsland),
        )
        assertEquals(listOf("Mental illness"), GenreSubgenres.breakdown("Drama", shutterIsland))
    }

    @Test
    fun aTagRepeatingTheGenreIsNotABreakdownOfIt() {
        assertEquals(
            listOf("Slasher"),
            GenreSubgenres.breakdown("Horror", listOf("horror", "Slasher")),
        )
    }

    @Test
    fun matchingIsExactNotSubstring() {
        assertEquals(emptyList(), GenreSubgenres.breakdown("War", listOf("warehouse", "software")))
    }

    @Test
    fun compoundTelevisionGenresReadBothHalves() {
        assertEquals(
            listOf("Space opera", "Dragon"),
            GenreSubgenres.breakdown("Sci-Fi & Fantasy", listOf("space opera", "dragon")),
        )
        assertEquals(
            listOf("Martial arts", "Treasure hunt"),
            GenreSubgenres.breakdown("Action & Adventure", listOf("treasure hunt", "martial arts")),
        )
    }

    @Test
    fun normalisesCaseAndWhitespace() {
        assertEquals(
            listOf("Found footage"),
            GenreSubgenres.breakdown("  HORROR ", listOf("Found   Footage ")),
        )
    }

    @Test
    fun capsTheListAndReturnsNothingForUnknownGenres() {
        val many = listOf(
            "slasher", "body horror", "found footage", "zombie", "vampire", "werewolf", "ghost", "demon",
        )
        assertEquals(GenreSubgenres.MAX_PER_GENRE, GenreSubgenres.breakdown("Horror", many).size)
        assertEquals(emptyList(), GenreSubgenres.breakdown("Talk", many))
        assertEquals(emptyList(), GenreSubgenres.breakdown("Horror", emptyList()))
    }
}
