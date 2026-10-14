package chat.donzi.localtavern.utils

import kotlin.test.Test
import kotlin.test.assertNull

// Cards whose text can never ride a sync envelope must fail at import, not
// wedge every future sync: a single row above the forwardability ceiling
// blocks its whole sequence group forever, and the exchange reports the same
// clear error on every attempt until the row is found and removed. Failing
// the card up front (by name, like any other unimportable card) keeps sync
// converging for everything else.
class CardTextRefusalTest {

    @Test
    fun oversizedCardText_failsImportInsteadOfWedgingSync() {
        val huge = "lore ".repeat(1_750_000)
        val card = """
            {"name":"Heavy Tome","description":"$huge","personality":"","scenario":"",
             "first_mes":"Hi","mes_example":"","creator_notes":"","system_prompt":"",
             "alternate_greetings":[]}
        """.trimIndent()
        assertNull(
            CharacterManager.processImport(card.encodeToByteArray(), "Heavy Tome.json"),
            "A card that can never sync must fail import instead of wedging sync"
        )
    }
}
