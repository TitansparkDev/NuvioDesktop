package com.nuvio.app.features.details

import kotlin.test.Test
import kotlin.test.assertEquals

class ImdbInterestsParserTest {

    @Test
    fun readsInterestsWithTheirCategoryAndDropsThePlainGenres() {
        val body = """
            {"data":{"title":{"interests":{"edges":[
              {"node":{"primaryText":{"text":"Dark Comedy"},"category":{"text":"Comedy"}}},
              {"node":{"primaryText":{"text":"Folk Horror"},"category":{"text":"Horror"}}},
              {"node":{"primaryText":{"text":"Horror"},"category":{"text":"Horror"}}},
              {"node":{"primaryText":{"text":"Folk Horror"},"category":{"text":"Horror"}}},
              {"node":{"primaryText":{"text":"Nameless"}}}
            ]}}},"extensions":{"disclaimer":"..."}}
        """.trimIndent()
        assertEquals(
            listOf(ImdbInterest("Dark Comedy", "Comedy"), ImdbInterest("Folk Horror", "Horror")),
            parseImdbInterests(body),
        )
    }

    @Test
    fun missingTitleReadsAsNoInterests() {
        assertEquals(emptyList(), parseImdbInterests("""{"data":{"title":null}}"""))
    }
}
