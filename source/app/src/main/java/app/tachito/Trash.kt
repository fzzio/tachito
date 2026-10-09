package app.tachito

import android.content.ContentResolver
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.util.Properties

const val TRASH_DAYS = 30L
private const val DAY_MS = 24 * 60 * 60 * 1000L
private const val TRASH_DIR = ".tachito-papelera"

/**
 * Papelera propia para archivos fuera de MediaStore (carpetas que Android oculta a la galería),
 * donde la papelera del sistema no funciona. Mueve el archivo a una carpeta oculta del mismo
 * volumen (rename, instantáneo) y guarda la ruta original en index.properties para restaurarlo.
 * Solo java.io: se prueba en JVM (TrashBinTest).
 */
class TrashBin(val dir: File) {
    data class Item(val file: File, val original: String, val trashedAt: Long) {
        val expiresAt get() = trashedAt + TRASH_DAYS * DAY_MS
    }

    private val index = File(dir, "index.properties")

    private fun load() = Properties().apply { if (index.exists()) index.inputStream().use { load(it) } }

    private fun save(p: Properties) {
        dir.mkdirs()
        index.outputStream().use { p.store(it, null) }
    }

    /** Devuelve los archivos originales que se pudieron mover. */
    fun moveIn(files: List<File>, now: Long = System.currentTimeMillis()): Set<File> {
        dir.mkdirs()
        File(dir, ".nomedia").createNewFile() // que la galería no muestre la papelera
        val p = load()
        val moved = files.filter { f ->
            var i = 0
            var target: File
            do target = File(dir, "${now}_${i++}_${f.name}") while (target.exists())
            f.renameTo(target).also { ok -> if (ok) p[target.name] = f.absolutePath }
        }
        save(p)
        return moved.toSet()
    }

    fun items(): List<Item> {
        val p = load()
        return p.stringPropertyNames().mapNotNull { name ->
            val f = File(dir, name)
            if (f.exists()) Item(f, p.getProperty(name), name.substringBefore('_').toLongOrNull() ?: 0) else null
        }
    }

    /** No pisa un archivo que ya exista en la ruta original. */
    fun restore(item: Item): Boolean {
        val dest = File(item.original)
        if (dest.exists()) return false
        dest.parentFile?.mkdirs()
        if (!item.file.renameTo(dest)) return false
        forget(listOf(item))
        return true
    }

    fun delete(items: List<Item>) {
        items.forEach { it.file.delete() }
        forget(items)
    }

    fun purge(now: Long = System.currentTimeMillis()) = delete(items().filter { it.expiresAt <= now })

    private fun forget(items: List<Item>) {
        val p = load()
        items.forEach { p.remove(it.file.name) }
        save(p)
    }
}

/** "/storage/emulated/0/DCIM/x.jpg" -> "/storage/emulated/0"; "/storage/1234-ABCD/x" -> "/storage/1234-ABCD". */
fun volumeRoot(path: String, primary: String): String =
    if (path.startsWith("$primary/") || path == primary) primary
    else "/storage/" + path.removePrefix("/storage/").substringBefore('/')

private val primaryRoot get() = Environment.getExternalStorageDirectory().path

fun binFor(path: String) = TrashBin(File(volumeRoot(path, primaryRoot), TRASH_DIR))

fun allBins(ctx: Context): List<TrashBin> =
    ctx.getSystemService(StorageManager::class.java).storageVolumes
        .mapNotNull { it.directory }
        .map { TrashBin(File(it, TRASH_DIR)) }

fun moveToOwnTrash(ctx: Context, items: List<Media>): List<Media> {
    val moved = items.groupBy { binFor(it.file!!).dir }.flatMap { (dir, l) ->
        val ok = TrashBin(dir).moveIn(l.map { File(it.file!!) })
        l.filter { File(it.file!!) in ok }
    }
    rescan(ctx, moved.map { it.file!! })
    return moved
}

/** Avisa a MediaStore que esas rutas cambiaron (si estaban indexadas, se actualizan). */
fun rescan(ctx: Context, paths: List<String>) {
    if (paths.isNotEmpty()) MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null, null)
}

/** "content://.../tree/primary:WhatsApp/Media" -> "/storage/emulated/0/WhatsApp/Media" */
fun treeIdToPath(treeDocumentId: String, primary: String = primaryRoot): String {
    val volume = treeDocumentId.substringBefore(':')
    val relative = treeDocumentId.substringAfter(':', "")
    val root = if (volume == "primary") primary else "/storage/$volume"
    return if (relative.isEmpty()) root else "$root/$relative"
}

/** Recorre la carpeta elegida y subcarpetas (sin entrar en carpetas ocultas). */
fun scanFolder(dir: File, files: Boolean): List<Media> {
    val mimes = MimeTypeMap.getSingleton()
    return dir.walkTopDown()
        .onEnter { it == dir || !it.name.startsWith(".") }
        .filter { it.isFile && !it.name.startsWith(".") }
        .map { f ->
            Media(
                id = f.absolutePath.hashCode().toLong(),
                mime = mimes.getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream",
                size = f.length(),
                date = f.lastModified(),
                album = f.parentFile?.name ?: "",
                path = f.parent ?: "",
                name = f.name,
                file = f.absolutePath,
            )
        }
        .filter { (it.isImage || it.isVideo) != files }
        .toList()
}

// --- Papelera del sistema (lo enviado con createTrashRequest o IS_TRASHED) ---

fun querySystemTrash(cr: ContentResolver): List<Media> {
    val args = Bundle().apply {
        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaColumns.SIZE} > 0 AND ${MediaColumns.MIME_TYPE} IS NOT NULL")
    }
    return cr.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), MEDIA_PROJECTION, args, null)
        ?.use { it.toMediaList() } ?: emptyList()
}

/** Para archivos no multimedia (documentos, zip...): el sistema no da diálogo, se hace directo. */
fun deleteNonMediaFromSystemTrash(cr: ContentResolver, items: List<Media>): Int {
    val extras = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
    return items.sumOf { runCatching { cr.delete(it.uri, extras) }.getOrDefault(0) }
}
