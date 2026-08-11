package social.saigon.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class AboutHostDisplayTest {

    private val host = OccupantView(
        userId = "u1",
        name = "Room Host",
        displayName = "Persistent Host",
        role = Role.SUPPORTER,
        languages = listOf("vi", "en"),
        interests = listOf("food", "music"),
        introduction = "I love showing people around.",
        photoUrl = "https://example.com/host.jpg",
    )

    @Test
    fun prefersHostOccupantFields() {
        val display = aboutHostDisplay(
            host = host,
            fallbackName = "Fallback",
            fallbackRole = Role.SHARER,
            fallbackLanguages = listOf("fr"),
            fallbackInterests = listOf("travel"),
        )

        assertEquals("Room Host", display.name)
        assertEquals(Role.SUPPORTER, display.role)
        assertEquals("https://example.com/host.jpg", display.photoUrl)
        assertEquals(listOf("food", "music"), display.interests)
        assertEquals(listOf("vi", "en"), display.languages)
        assertEquals("I love showing people around.", display.introduction)
    }

    @Test
    fun fallsBackWhenHostMissing() {
        val display = aboutHostDisplay(
            host = null,
            fallbackName = "Fallback Host",
            fallbackRole = Role.FOUNDER,
            fallbackLanguages = listOf("en"),
            fallbackInterests = listOf("coffee"),
        )

        assertEquals("Fallback Host", display.name)
        assertEquals(Role.FOUNDER, display.role)
        assertEquals("", display.photoUrl)
        assertEquals(listOf("coffee"), display.interests)
        assertEquals(listOf("en"), display.languages)
        assertEquals("", display.introduction)
    }

    @Test
    fun blankHostNameUsesFallback() {
        val display = aboutHostDisplay(
            host = host.copy(name = "  "),
            fallbackName = "Fallback Host",
            fallbackRole = Role.SHARER,
            fallbackLanguages = emptyList(),
            fallbackInterests = emptyList(),
        )

        assertEquals("Fallback Host", display.name)
    }
}
