package app.tachito

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.provider.BaseColumns
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import android.provider.MediaStore.MediaColumns

data class Media(
    val id: Long,
    val mime: String,
    val size: Long,
    val date: Long,
    val album: String,
    val path: String,
    val name: String = "",
    val durationMs: Long = 0,
    val owner: String = "",
    /** Ruta absoluta si viene de "Elegir otra carpeta" (fuera de MediaStore). Esos van a la papelera propia. */
    val file: String? = null,
    /** Solo en papelera: cuándo se borra solo (ms). */
    val expires: Long = 0,
) {
    val isImage get() = mime.startsWith("image/")
    val isVideo get() = mime.startsWith("video/")
    val isAudio get() = mime.startsWith("audio/")
    val isMediaItem get() = isImage || isVideo || isAudio

    val uri: Uri
        get() = when {
            file != null -> Uri.fromFile(java.io.File(file))
            isImage -> ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            isVideo -> ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
            isAudio -> ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
            else -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL, id)
        }

    val kind: Kind
        get() = when {
            isImage -> Kind.PHOTOS
            isVideo -> Kind.VIDEOS
            isAudio -> Kind.AUDIO
            mime == "application/vnd.android.package-archive" -> Kind.APKS
            ARCHIVE_HINTS.any { mime.contains(it) } -> Kind.ARCHIVES
            mime.startsWith("text/") || DOC_HINTS.any { mime.contains(it) } -> Kind.DOCS
            else -> Kind.OTHER
        }
}

private val DOC_HINTS = listOf("pdf", "msword", "officedocument", "opendocument", "ms-excel", "ms-powerpoint", "rtf", "epub")
private val ARCHIVE_HINTS = listOf("zip", "rar", "7z", "tar", "gzip")

/** files = null: aplica a ambos modos. */
enum class Kind(val label: String, val files: Boolean?) {
    ALL("Todo", null),
    PHOTOS("Fotos", false),
    VIDEOS("Videos", false),
    AUDIO("Audio", true),
    DOCS("Documentos", true),
    ARCHIVES("Comprimidos", true),
    APKS("Instaladores", true),
    OTHER("Otros", true),
}

enum class Order(val label: String) {
    BIGGEST("Más pesados"), OLDEST("Más antiguos"), NEWEST("Más recientes"), RANDOM("Aleatorio")
}

enum class Preset(val label: String, val test: (Media) -> Boolean) {
    CAMERA("Galería (cámara)", { it.path.startsWith("DCIM/", true) }),
    SCREENSHOTS("Capturas de pantalla", { it.path.contains("screenshot", true) }),
    WHATSAPP("WhatsApp", { it.path.contains("whatsapp", true) || it.owner.startsWith("com.whatsapp") }),
    // Solo copias locales: Google Fotos no permite tocar la nube.
    GOOGLE_PHOTOS("Google Fotos", { it.owner == "com.google.android.apps.photos" || it.path.contains("Google Photos", true) }),
    TELEGRAM("Telegram", { it.path.contains("telegram", true) || it.owner.startsWith("org.telegram") }),
    DOWNLOADS("Descargas", { it.path.startsWith("Download/", true) }),
}

sealed interface Source {
    data class Group(val preset: Preset) : Source
    data class Album(val name: String) : Source
}

data class Filter(
    val files: Boolean = false,
    /** Vacío = todas las fuentes. Con varias, se suman (unión). */
    val sources: Set<Source> = emptySet(),
    val kind: Kind = Kind.ALL,
    val order: Order = Order.BIGGEST,
)

fun Media.matches(kind: Kind) = kind == Kind.ALL || this.kind == kind

fun Media.matches(source: Source) = when (source) {
    is Source.Group -> source.preset.test(this)
    is Source.Album -> album == source.name
}

fun List<Media>.select(f: Filter): List<Media> {
    val l = filter { it.matches(f.kind) && (f.sources.isEmpty() || f.sources.any(it::matches)) }
    return when (f.order) {
        Order.BIGGEST -> l.sortedByDescending { it.size }
        Order.OLDEST -> l.sortedBy { it.date }
        Order.NEWEST -> l.sortedByDescending { it.date }
        Order.RANDOM -> l.shuffled()
    }
}

val MEDIA_PROJECTION = arrayOf(
    BaseColumns._ID, MediaColumns.MIME_TYPE, MediaColumns.SIZE, MediaColumns.DATE_TAKEN, MediaColumns.DATE_ADDED,
    MediaColumns.BUCKET_DISPLAY_NAME, MediaColumns.RELATIVE_PATH, MediaColumns.DISPLAY_NAME,
    MediaColumns.DURATION, MediaColumns.OWNER_PACKAGE_NAME, MediaColumns.DATE_EXPIRES,
)

fun Cursor.toMediaList(): List<Media> = buildList {
    while (moveToNext()) add(
        Media(
            id = getLong(0),
            mime = getString(1) ?: "",
            size = getLong(2),
            date = getLong(3).takeIf { it > 0 } ?: (getLong(4) * 1000),
            album = getString(5) ?: "Sin carpeta",
            path = getString(6) ?: "",
            name = getString(7) ?: "",
            durationMs = getLong(8),
            owner = getString(9) ?: "",
            expires = getLong(10) * 1000,
        )
    )
}

// Elementos en la papelera del sistema no aparecen: MediaStore los excluye por defecto.
fun queryMedia(cr: ContentResolver, files: Boolean): List<Media> {
    val visual = "${FileColumns.MEDIA_TYPE_IMAGE}, ${FileColumns.MEDIA_TYPE_VIDEO}"
    val selection =
        if (files) "${FileColumns.MEDIA_TYPE} NOT IN ($visual) AND ${MediaColumns.MIME_TYPE} IS NOT NULL AND ${MediaColumns.SIZE} > 0"
        else "${FileColumns.MEDIA_TYPE} IN ($visual)"
    return cr.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), MEDIA_PROJECTION, selection, null, null)
        ?.use { it.toMediaList() } ?: emptyList()
}
