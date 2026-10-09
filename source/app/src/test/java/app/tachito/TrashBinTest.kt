package app.tachito

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TrashBinTest {
    private val day = 24 * 60 * 60 * 1000L

    @Test
    fun moveRestorePurge() {
        val root = Files.createTempDirectory("tachito").toFile()
        val a = File(root, "Sent/a.jpg").apply { parentFile.mkdirs(); writeText("a") }
        val b = File(root, "Sent/b.jpg").apply { writeText("b") }
        val bin = TrashBin(File(root, ".tachito-papelera"))

        assertEquals(setOf(a, b), bin.moveIn(listOf(a, b), now = 1000))
        assertFalse(a.exists())
        assertTrue(File(bin.dir, ".nomedia").exists())
        assertEquals(2, bin.items().size)

        // mismo nombre otra vez: no pisa al anterior
        val a2 = File(root, "Sent/a.jpg").apply { writeText("a2") }
        bin.moveIn(listOf(a2), now = 1000)
        assertEquals(3, bin.items().size)

        val itemA = bin.items().first { it.file.readText() == "a" }
        File(root, "Sent/a.jpg").writeText("ocupado")
        assertFalse("no pisa un archivo existente", bin.restore(itemA))
        File(root, "Sent/a.jpg").delete()
        assertTrue(bin.restore(itemA))
        assertEquals("a", File(root, "Sent/a.jpg").readText())
        assertEquals(2, bin.items().size)

        bin.moveIn(listOf(File(root, "Sent/a.jpg")), now = 1000 + 20 * day)
        bin.purge(now = 1000 + TRASH_DAYS * day) // vence b y a2, no el reciente
        assertEquals(listOf("a"), bin.items().map { it.file.readText() })

        bin.delete(bin.items())
        assertTrue(bin.items().isEmpty())
        root.deleteRecursively()
    }

    @Test
    fun paths() {
        val p = "/storage/emulated/0"
        assertEquals(p, volumeRoot("$p/DCIM/x.jpg", p))
        assertEquals("/storage/1234-ABCD", volumeRoot("/storage/1234-ABCD/DCIM/x.jpg", p))
        assertEquals("$p/WhatsApp/Media", treeIdToPath("primary:WhatsApp/Media", p))
        assertEquals(p, treeIdToPath("primary:", p))
        assertEquals("/storage/1234-ABCD/Fotos", treeIdToPath("1234-ABCD:Fotos", p))
    }
}
