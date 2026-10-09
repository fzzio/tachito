package app.tachito

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.media.ThumbnailUtils
import android.os.ParcelFileDescriptor
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

private const val MAX_ZOOM = 8f

/** Foto o miniatura de video reducida a maxSide (evita cargar fotos de 50MP en memoria). */
fun loadBitmap(ctx: Context, m: Media, maxSide: Int): Bitmap? = runCatching {
    val file = m.file?.let(::File)
    val size = Size(maxSide * 9 / 16, maxSide)
    when {
        m.isVideo && file != null -> ThumbnailUtils.createVideoThumbnail(file, size, null)
        m.isVideo -> ctx.contentResolver.loadThumbnail(m.uri, size, null)
        else -> ImageDecoder.decodeBitmap(
            if (file != null) ImageDecoder.createSource(file) else ImageDecoder.createSource(ctx.contentResolver, m.uri)
        ) { decoder, info, _ ->
            var sample = 1
            while (maxOf(info.size.width, info.size.height) / (sample * 2) >= maxSide) sample *= 2
            decoder.setTargetSampleSize(sample)
        }
    }
}.getOrNull()

/** PDF con el PdfRenderer de Android. No es thread-safe: render y close van sincronizados. */
class Pdf private constructor(private val fd: ParcelFileDescriptor) : Closeable {
    private val renderer = PdfRenderer(fd)
    private var closed = false
    val pages = renderer.pageCount

    @Synchronized
    fun render(i: Int, maxSide: Int): ImageBitmap? {
        if (closed) return null
        return renderer.openPage(i).use { p ->
            val s = maxSide.toFloat() / maxOf(p.width, p.height)
            val bmp = Bitmap.createBitmap((p.width * s).toInt().coerceAtLeast(1), (p.height * s).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp.asImageBitmap()
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { renderer.close() }
        runCatching { fd.close() }
    }

    companion object {
        /** null si no se puede abrir (dañado o con contraseña). */
        fun open(ctx: Context, m: Media): Pdf? {
            val fd = runCatching {
                m.file?.let { ParcelFileDescriptor.open(File(it), ParcelFileDescriptor.MODE_READ_ONLY) }
                    ?: ctx.contentResolver.openFileDescriptor(m.uri, "r")
            }.getOrNull() ?: return null
            return runCatching { Pdf(fd) }.onFailure { runCatching { fd.close() } }.getOrNull()
        }
    }
}

/** Visor a pantalla completa: pellizcar o doble toque para zoom, botones +/− y páginas si hay varias. */
@Composable
fun ZoomViewer(title: String, pages: Int, onClose: () -> Unit, load: suspend (Int) -> ImageBitmap?) {
    var page by remember { mutableIntStateOf(0) }
    var scale by remember(page) { mutableFloatStateOf(1f) }
    var pan by remember(page) { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val bitmap by produceState<ImageBitmap?>(null, page) { value = null; value = withContext(Dispatchers.IO) { load(page) } }

    // El pan no puede sacar la imagen del borde
    fun clamp(p: Offset) = Offset(
        p.x.coerceIn(-(scale - 1) * box.width / 2, (scale - 1) * box.width / 2),
        p.y.coerceIn(-(scale - 1) * box.height / 2, (scale - 1) * box.height / 2),
    )
    fun zoom(to: Float) {
        scale = to.coerceIn(1f, MAX_ZOOM)
        pan = clamp(pan)
    }

    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val image = bitmap
            if (image == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            else Image(
                image, title,
                Modifier
                    .fillMaxSize()
                    .pointerInput(page) {
                        box = size
                        detectTransformGestures { _, p, z, _ -> zoom(scale * z); pan = clamp(pan + p) }
                    }
                    .pointerInput(page) { detectTapGestures(onDoubleTap = { zoom(if (scale > 1f) 1f else 2.5f) }) }
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y },
                contentScale = ContentScale.Fit,
            )

            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).statusBarsPadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClose) { Icon(Icons.Default.Close, "Cerrar", tint = Color.White) }
                Text(title, Modifier.weight(1f), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (pages > 1) {
                    FilledTonalIconButton({ page-- }, enabled = page > 0) { Icon(Icons.Default.KeyboardArrowLeft, "Página anterior") }
                    Text("${page + 1} / $pages", color = Color.White)
                    FilledTonalIconButton({ page++ }, enabled = page < pages - 1) { Icon(Icons.Default.KeyboardArrowRight, "Página siguiente") }
                    Spacer(Modifier.width(16.dp))
                }
                FilledTonalIconButton({ zoom(scale / 1.5f) }, enabled = scale > 1f) { Icon(AppIcons.ZoomOut, "Alejar") }
                FilledTonalIconButton({ zoom(scale * 1.5f) }, enabled = scale < MAX_ZOOM) { Icon(AppIcons.ZoomIn, "Acercar") }
            }
        }
    }
}
