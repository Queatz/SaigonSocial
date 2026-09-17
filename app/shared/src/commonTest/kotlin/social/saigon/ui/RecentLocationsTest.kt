package social.saigon.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class RecentLocationsTest {

    private val cafe = ServiceDestination(
        name = "Café RuNam",
        address = "158 Đồng Khởi",
        url = "https://maps.google.com/?q=runam",
    )
    private val market = ServiceDestination(
        name = "Bến Thành Market",
        address = "Lê Lợi",
        url = "https://maps.google.com/?q=benthanh",
    )

    @Test
    fun newestMeetsComeFirst() {
        val older = service("old", createdAt = 1, destinations = listOf(market))
        val newer = service("new", createdAt = 9, destinations = listOf(cafe))

        assertEquals(
            listOf(cafe, market),
            recentLocationsFromServices(listOf(older, newer)),
        )
    }

    @Test
    fun whollyIdenticalLocationsAppearOnce() {
        val first = cafe.copy(photos = listOf(ProfilePhoto(url = "https://cdn/new.jpg")))
        val duplicate = cafe.copy(photos = listOf(ProfilePhoto(url = "https://cdn/old.jpg")))
        val services = listOf(
            service("a", createdAt = 2, destinations = listOf(first)),
            service("b", createdAt = 1, destinations = listOf(duplicate)),
        )

        val recent = recentLocationsFromServices(services)
        assertEquals(listOf(first), recent)
        assertEquals("https://cdn/new.jpg", recent.single().photos.single().url)
    }

    @Test
    fun trimmedFieldsCountAsIdentical() {
        val padded = ServiceDestination(
            name = "  Café RuNam ",
            address = "158 Đồng Khởi  ",
            url = " https://maps.google.com/?q=runam ",
        )
        val services = listOf(
            service("a", createdAt = 2, destinations = listOf(cafe)),
            service("b", createdAt = 1, destinations = listOf(padded)),
        )

        assertEquals(listOf(cafe), recentLocationsFromServices(services))
    }

    @Test
    fun differentAddressKeepsBoth() {
        val otherCafe = cafe.copy(address = "Another street")
        val services = listOf(
            service("a", createdAt = 2, destinations = listOf(cafe)),
            service("b", createdAt = 1, destinations = listOf(otherCafe)),
        )

        assertEquals(listOf(cafe, otherCafe), recentLocationsFromServices(services))
    }

    @Test
    fun skipsBlankDestinationsAndExcludedMeet() {
        val blank = ServiceDestination()
        val services = listOf(
            service("current", createdAt = 3, destinations = listOf(market)),
            service("other", createdAt = 2, destinations = listOf(blank, cafe)),
        )

        assertEquals(
            listOf(cafe),
            recentLocationsFromServices(services, excludeServiceId = "current"),
        )
    }

    @Test
    fun respectsLimit() {
        val services = (1..5).map { i ->
            service(
                id = "s$i",
                createdAt = i.toLong(),
                destinations = listOf(ServiceDestination(name = "Stop $i", address = "Addr $i")),
            )
        }

        assertEquals(
            listOf("Stop 5", "Stop 4"),
            recentLocationsFromServices(services, limit = 2).map { it.name },
        )
    }

    @Test
    fun applyFillsFirstBlankStopOtherwiseAppends() {
        val destinations = mutableListOf(cafe, ServiceDestination())
        applyRecentLocation(destinations, market)
        assertEquals(listOf(cafe, market), destinations)

        applyRecentLocation(destinations, cafe)
        assertEquals(listOf(cafe, market, cafe), destinations)
    }

    private fun service(
        id: String,
        createdAt: Long,
        destinations: List<ServiceDestination>,
    ) = ServiceView(
        id = id,
        emoji = "☕",
        name = "Meet $id",
        roomId = "r1",
        roomName = "Room",
        providerId = "p1",
        providerName = "Host",
        destinations = destinations,
        createdAt = createdAt,
    )
}
