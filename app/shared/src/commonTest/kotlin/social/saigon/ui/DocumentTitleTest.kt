package social.saigon.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class DocumentTitleTest {

    @Test
    fun documentTitleKeepsHomeBrandAlone() {
        assertEquals("Saigon.Social", documentTitle("Saigon.Social"))
    }

    @Test
    fun documentTitleAppendsBrandSuffixElsewhere() {
        assertEquals("🏠 Coffee Chat • Saigon.Social", documentTitle("🏠 Coffee Chat"))
        assertEquals("Meets • Saigon.Social", documentTitle("Meets"))
        assertEquals("Your profile • Saigon.Social", documentTitle("Your profile"))
    }

    @Test
    fun roomTitleMatchesTopBarEmojiAndName() {
        assertEquals("🏠 Coffee Chat", roomTopBarTitle("🏠", "Coffee Chat"))
    }

    @Test
    fun roomTitleTrimsBlankEnds() {
        assertEquals("Quiet Room", roomTopBarTitle("", "Quiet Room"))
        assertEquals("🌟", roomTopBarTitle("🌟", "  "))
    }

    @Test
    fun serviceTitleFallsBackUntilNameLoads() {
        assertEquals("Meets", serviceTopBarTitle(null))
        assertEquals("Meets", serviceTopBarTitle(null, "🌟"))
    }

    @Test
    fun serviceTitleUsesNameAloneWhenEmojiBlank() {
        assertEquals("Street Food Walk", serviceTopBarTitle("Street Food Walk", ""))
        assertEquals("Street Food Walk", serviceTopBarTitle("Street Food Walk", "   "))
    }

    @Test
    fun serviceTitlePrefersEmojiPlusName() {
        assertEquals("🍜 Pho Tour", serviceTopBarTitle("Pho Tour", "🍜"))
    }
}
