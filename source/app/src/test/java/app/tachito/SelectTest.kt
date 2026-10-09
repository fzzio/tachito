package app.tachito

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectTest {
    private val all = listOf(
        Media(1, "image/jpeg", size = 10, date = 300, album = "Camera", path = "DCIM/Camera/"),
        Media(2, "video/mp4", size = 500, date = 100, album = "WhatsApp Video", path = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/"),
        Media(3, "image/jpeg", size = 50, date = 200, album = "WhatsApp Images", path = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"),
        Media(4, "video/mp4", size = 90, date = 400, album = "Camera", path = "DCIM/Camera/"),
        Media(5, "image/png", size = 5, date = 50, album = "Pictures", path = "Pictures/", owner = "com.google.android.apps.photos"),
        Media(6, "image/png", size = 7, date = 60, album = "Screenshots", path = "Pictures/Screenshots/"),
    )
    private val files = listOf(
        Media(10, "audio/ogg", size = 3, date = 1, album = "WhatsApp Voice Notes", path = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/"),
        Media(11, "application/pdf", size = 9, date = 2, album = "Download", path = "Download/"),
        Media(12, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", size = 4, date = 3, album = "Download", path = "Download/"),
        Media(13, "application/zip", size = 8, date = 4, album = "Download", path = "Download/"),
        Media(14, "application/vnd.android.package-archive", size = 6, date = 5, album = "Download", path = "Download/"),
    )

    private fun ids(f: Filter, l: List<Media> = all) = l.select(f).map { it.id }
    private fun group(p: Preset) = Source.Group(p)

    @Test
    fun galleryFiltersAndOrders() {
        assertEquals(listOf(2L, 4L, 3L, 1L, 6L, 5L), ids(Filter()))
        assertEquals(listOf(2L, 3L), ids(Filter(source = group(Preset.WHATSAPP))))
        assertEquals(listOf(3L), ids(Filter(source = group(Preset.WHATSAPP), kind = Kind.PHOTOS)))
        assertEquals(listOf(1L, 4L), ids(Filter(source = group(Preset.CAMERA), order = Order.OLDEST)))
        assertEquals(listOf(5L), ids(Filter(source = group(Preset.GOOGLE_PHOTOS))))
        assertEquals(listOf(6L), ids(Filter(source = group(Preset.SCREENSHOTS))))
        assertEquals(listOf(1L, 4L), ids(Filter(source = Source.Album("Camera"), order = Order.OLDEST)))
        assertEquals(listOf(4L, 2L), ids(Filter(kind = Kind.VIDEOS, order = Order.NEWEST)))
        assertEquals(all.map { it.id }.toSet(), ids(Filter(order = Order.RANDOM)).toSet())
    }

    @Test
    fun fileKinds() {
        assertEquals(listOf(Kind.AUDIO, Kind.DOCS, Kind.DOCS, Kind.ARCHIVES, Kind.APKS), files.map { it.kind })
        assertEquals(listOf(11L, 12L), ids(Filter(files = true, kind = Kind.DOCS), files))
        assertEquals(listOf(10L), ids(Filter(files = true, source = group(Preset.WHATSAPP)), files))
        assertEquals(listOf(11L, 13L, 14L, 12L), ids(Filter(files = true, source = group(Preset.DOWNLOADS)), files))
    }
}
