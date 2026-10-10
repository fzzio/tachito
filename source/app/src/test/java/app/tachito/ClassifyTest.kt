package app.tachito

import org.junit.Assert.assertEquals
import org.junit.Test

class ClassifyTest {
    private fun c(text: String, camera: Boolean = false) = classify(text, camera)

    @Test
    fun categories() {
        assertEquals(Category.GREETINGS, c("¡Buenos días! Que tengas un lindo día"))
        assertEquals(Category.RELIGION, c("Que Dios te bendiga hoy y siempre. Amén"))
        assertEquals(Category.MARKETING, c("GRAN OFERTA 50% de descuento solo por hoy"))
        assertEquals(Category.NEWS, c("ÚLTIMA HORA: el Gobierno anunció un nuevo decreto"))
        assertEquals(Category.HEALTH, c("Beneficios del limón con jengibre para la salud"))
        assertEquals(Category.WORK, c("Factura N° 001 Subtotal 100 IVA 15 Total 115"))
        assertEquals(Category.MEMES, c("cuando el profe dice que no hay tarea"))
    }

    @Test
    fun noTextOrCamera() {
        assertEquals(Category.NONE, c(""))
        assertEquals(Category.NONE, c("ok jaja"))
        // Fotos de cámara nunca se clasifican: no queremos sugerir borrar fotos propias
        assertEquals(Category.NONE, c("Que Dios te bendiga hoy y siempre", camera = true))
    }

    @Test
    fun keywordsAreWordPrefixes() {
        assertEquals(" bendiciones para todos ", normalize("¡Bendiciones para todos!"))
        // "fe" no debe encontrarse dentro de "café"
        assertEquals(Category.MEMES, c("yo tomando café a las tres de la mañana"))
    }

    @Test
    fun contentFilter() {
        val tags = mapOf(
            1L to Tag(false, Category.RELIGION),
            2L to Tag(false, Category.MEMES),
            3L to Tag(true, Category.NONE),
        )
        val all = listOf(1L, 2L, 3L, 4L).map { Media(it, "image/jpeg", size = 1, date = it, album = "", path = "") }
        fun ids(vararg c: Content) = all.select(Filter(contents = c.toSet()), tags).map { it.id }
        assertEquals(listOf(1L, 2L, 3L, 4L), ids())
        assertEquals(listOf(1L), ids(Content.RELIGION))
        assertEquals(listOf(1L, 2L), ids(Content.RELIGION, Content.MEMES))
        assertEquals(listOf(1L, 2L), ids(Content.RECEIVED)) // 4 aún no analizada: no entra
    }
}
