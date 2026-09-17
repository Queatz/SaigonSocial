package social.saigon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServiceCopyTest {

    @Test
    fun servicesProvidedLabelUsesMeetWording() {
        assertEquals("1 hosted meet", servicesProvidedLabel(1))
        assertEquals("0 hosted meets", servicesProvidedLabel(0))
        assertEquals("3 hosted meets", servicesProvidedLabel(3))
    }

    @Test
    fun cancellationPolicyHiddenWhenFreeOrBlank() {
        assertFalse(shouldShowCancellationPolicy(price = 0, policy = "Cancel anytime"))
        assertFalse(shouldShowCancellationPolicy(price = 150_000, policy = ""))
        assertFalse(shouldShowCancellationPolicy(price = 0, policy = "   "))
        assertTrue(shouldShowCancellationPolicy(price = 150_000, policy = "Cancel anytime"))
    }

    @Test
    fun photoChangesDetectAddDeleteAndCaptionUpdate() {
        val original = listOf(
            ProfilePhoto(url = "https://cdn/a.jpg", message = "A"),
            ProfilePhoto(url = "https://cdn/b.jpg", message = "B"),
        )
        val current = listOf(
            ProfilePhoto(url = "https://cdn/b.jpg", message = "B updated"),
            ProfilePhoto(url = "https://cdn/c.jpg", message = "C"),
        )
        val changes = servicePhotoChanges(original, current)
        assertEquals(listOf("https://cdn/a.jpg"), changes.toDeleteUrls)
        assertEquals(listOf("https://cdn/c.jpg"), changes.toAdd.map { it.url })
        assertEquals(listOf("https://cdn/b.jpg"), changes.toUpdate.map { it.url })
        assertEquals(listOf("B updated"), changes.toUpdate.map { it.message })
    }
}
