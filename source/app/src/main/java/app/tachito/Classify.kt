package app.tachito

import android.content.Context
import android.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.text.Normalizer

/** Tipo de imagen según el texto que contiene. Solo se clasifica lo que no salió de una cámara. */
enum class Category(val label: String, val keywords: List<String>) {
    GREETINGS(
        "Saludos (buenos días...)",
        listOf("buenos dias", "buenas tardes", "buenas noches", "feliz lunes", "feliz martes", "feliz miercoles", "feliz jueves",
            "feliz viernes", "feliz sabado", "feliz domingo", "feliz dia", "feliz fin de semana", "feliz semana", "lindo dia",
            "hermoso dia", "bonito dia", "excelente dia", "linda noche", "dulces suenos", "feliz cumple", "feliz navidad",
            "feliz ano", "te deseo", "para ti", "un abrazo", "saludos"),
    ),
    RELIGION(
        "Religión",
        listOf("dios", "jesus", "cristo", "senor", "bendic", "bendito", "bendecid", "amen", "oracion", "orar", "ora ", "virgen",
            "maria", "espiritu santo", "salmo", "biblia", "iglesia", "santo", "santa", "fe ", "milagro", "gloria", "padre nuestro",
            "rosario", "angel", "cielo", "evangelio", "versiculo", "proverbios", "juan ", "mateo", "san "),
    ),
    MARKETING(
        "Promociones",
        listOf("oferta", "descuento", "dcto", "promo", "gratis", "precio", "compra", "envio", "delivery", "pedido", "llama",
            "contactanos", "escribenos", "whatsapp", "sorteo", "2x1", "black friday", "cyber", "liquidacion", "rebaja", "ahorra",
            "solo por hoy", "aprovecha", "stock", "disponible", "cupon", "regalo", "tienda", "pvp", "precio especial", "por mayor",
            "info al", "inbox", "agenda tu", "reserva"),
    ),
    NEWS(
        "Noticias",
        listOf("ultima hora", "urgente", "noticia", "gobierno", "president", "ministr", "policia", "asamblea", "eleccion",
            "comunicado", "boletin", "informo", "segun", "fiscal", "alcald", "municipio", "decreto", "ley ", "denuncia",
            "fallecio", "accidente", "emergencia", "atencion", "se confirma", "oficial", "alerta", "breaking", "en vivo"),
    ),
    HEALTH(
        "Salud",
        listOf("salud", "medico", "doctor", "sintoma", "vacuna", "covid", "enfermedad", "cancer", "presion", "diabetes",
            "remedio", "vitamina", "hospital", "dieta", "colesterol", "corazon", "infarto", "virus", "dolor", "tratamiento",
            "beneficios", "natural", "limon", "jengibre", "ajo", "te de", "adelgazar", "nutricion", "rinon", "higado"),
    ),
    WORK(
        "Trabajo",
        listOf("reunion", "factura", "cotizacion", "proyecto", "cliente", "proveedor", "subtotal", "iva", "ruc", "informe",
            "reporte", "agenda", "pendiente", "entrega", "contrato", "nomina", "planilla", "deposito", "transferencia",
            "comprobante", "cuenta", "banco", "saldo", "orden de", "fecha", "total", "horario", "turno", "oficina"),
    ),
    MEMES("Memes y otras con texto", emptyList()),
    NONE("Sin texto o de cámara", emptyList());
}

/** Resultado del análisis de una imagen; [camera] = trae marca/modelo de cámara en EXIF. */
data class Tag(val camera: Boolean, val category: Category)

/** Lo que se puede marcar en "Por contenido". RECEIVED (category = null) cruza todas las categorías. */
enum class Content(val category: Category?) {
    RECEIVED(null), GREETINGS(Category.GREETINGS), RELIGION(Category.RELIGION), MARKETING(Category.MARKETING),
    NEWS(Category.NEWS), HEALTH(Category.HEALTH), WORK(Category.WORK), MEMES(Category.MEMES);

    val label get() = category?.label ?: "Recibidas"
    fun test(t: Tag) = if (category == null) !t.camera else t.category == category
}

private val MARKS = Regex("\\p{M}+")
private val NON_WORD = Regex("[^a-z0-9%$]+")

/** Minúsculas, sin tildes ni signos: "¡Bendiciones!" -> " bendiciones ". */
fun normalize(text: String): String =
    " " + Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(MARKS, "").replace(NON_WORD, " ").trim() + " "

/**
 * Gana la categoría con más palabras clave encontradas (empate: la primera en el enum).
 * Las palabras clave son prefijos de palabra: "bendic" encuentra "bendiciones".
 * ponytail: heurística por palabras clave; si falla mucho, pasar a un clasificador de imágenes.
 */
fun classify(text: String, camera: Boolean): Category {
    if (camera) return Category.NONE
    val t = normalize(text)
    val words = t.split(' ').count { it.length > 1 }
    if (words < 3) return Category.NONE
    val scores = Category.entries.associateWith { c -> c.keywords.count { t.contains(" $it") } }.toMutableMap()
    if ("%" in t || "$" in t) scores[Category.MARKETING] = scores.getValue(Category.MARKETING) + 1
    val best = scores.maxBy { it.value } // maxBy devuelve el primero con el máximo: respeta el orden del enum
    return if (best.value > 0) best.key else Category.MEMES
}

/** Resultados guardados en un archivo de texto ("id camara categoria" por línea); se agrega al final. */
class TagStore(private val file: File) {
    fun load(): Map<Long, Tag> =
        if (!file.exists()) emptyMap()
        else file.readLines().mapNotNull { line ->
            val p = line.split(' ')
            val cat = p.getOrNull(2)?.let { n -> Category.entries.find { it.name == n } } ?: return@mapNotNull null
            p[0].toLongOrNull()?.let { it to Tag(p[1] == "1", cat) }
        }.toMap()

    // ponytail: nunca se limpian ids de imágenes ya borradas; compactar si el archivo crece demasiado.
    fun add(tags: Map<Long, Tag>) =
        file.appendText(tags.entries.joinToString("") { (id, t) -> "$id ${if (t.camera) 1 else 0} ${t.category.name}\n" })
}

fun tagStore(ctx: Context) = TagStore(File(ctx.filesDir, "clasificacion.txt"))

private fun hasCameraExif(ctx: Context, m: Media): Boolean = runCatching {
    ctx.contentResolver.openInputStream(m.uri)!!.use {
        val exif = ExifInterface(it)
        exif.getAttribute(ExifInterface.TAG_MAKE) != null || exif.getAttribute(ExifInterface.TAG_MODEL) != null
    }
}.getOrDefault(false)

/** OCR en el teléfono (ML Kit vía Play Services). Llamar fuera del hilo principal. */
class Classifier(private val ctx: Context) : AutoCloseable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** null si el OCR falló (ej. Play Services aún bajando el modelo): no se guarda y se reintenta en otra apertura. */
    fun tag(m: Media): Tag? {
        if (hasCameraExif(ctx, m)) return Tag(true, Category.NONE)
        val bmp = loadBitmap(ctx, m, 1024) ?: return Tag(false, Category.NONE) // archivo ilegible: no reintentar
        return try {
            Tag(false, classify(Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0))).text, false))
        } catch (e: Exception) {
            null
        } finally {
            bmp.recycle()
        }
    }

    override fun close() = recognizer.close()
}
