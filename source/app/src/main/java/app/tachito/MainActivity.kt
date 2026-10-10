package app.tachito

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import java.io.File
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

private val PERMISSIONS =
    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

private fun hasMediaPermission(ctx: Context) =
    PERMISSIONS.any { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

fun Context.fmt(bytes: Long) = Formatter.formatShortFileSize(this, bytes)

fun Context.prefs() = getSharedPreferences("tachito", Context.MODE_PRIVATE)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TachitoTheme { App() }
        }
    }
}

@Composable
private fun OnLifecycle(onEvent: (Lifecycle.Event) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val current by rememberUpdatedState(onEvent)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> current(e) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    var mediaGranted by remember { mutableStateOf(hasMediaPermission(ctx)) }
    var filesGranted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    var asked by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        mediaGranted = hasMediaPermission(ctx)
        asked = true
    }
    // El acceso a todos los archivos se da en Ajustes: se revisa al volver a la app.
    OnLifecycle {
        if (it == Lifecycle.Event.ON_RESUME) {
            filesGranted = Environment.isExternalStorageManager()
            mediaGranted = hasMediaPermission(ctx)
        }
    }
    if (!mediaGranted && !filesGranted) {
        PermissionScreen(asked) { permLauncher.launch(PERMISSIONS) }
        return
    }

    var filter by remember { mutableStateOf(Filter()) }
    var all by remember { mutableStateOf<List<Media>?>(null) }
    var deck by remember { mutableStateOf<List<Media>?>(null) }
    var trashOpen by remember { mutableStateOf(false) }
    var folderPath by remember { mutableStateOf<String?>(null) }
    var folderItems by remember { mutableStateOf<List<Media>?>(null) }
    var trash by remember { mutableStateOf(0 to 0L) }
    val needsFilesAccess = filter.files && !filesGranted
    val onMenu = deck == null && !trashOpen

    // La papelera propia se vacía sola pasados los 30 días.
    LaunchedEffect(filesGranted) {
        if (filesGranted) withContext(Dispatchers.IO) { allBins(ctx).forEach { runCatching { it.purge() } } }
    }
    // Recarga al volver al menú: lo enviado a la papelera ya no aparece.
    LaunchedEffect(onMenu, filter.files, filesGranted) {
        if (!onMenu) return@LaunchedEffect
        all = null
        all = if (needsFilesAccess) emptyList() else withContext(Dispatchers.IO) { queryMedia(ctx.contentResolver, filter.files) }
        trash = withContext(Dispatchers.IO) { loadTrash(ctx).let { l -> l.size to l.sumOf { it.media.size } } }
    }
    LaunchedEffect(onMenu, folderPath, filter.files) {
        val path = folderPath ?: return@LaunchedEffect
        if (!onMenu) return@LaunchedEffect
        folderItems = null
        folderItems = withContext(Dispatchers.IO) { scanFolder(File(path), filter.files) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) folderPath = treeIdToPath(DocumentsContract.getTreeDocumentId(uri))
    }

    val d = deck
    when {
        trashOpen -> TrashScreen(onBack = { trashOpen = false })
        d != null -> SwipeScreen(d, resumeKey(filter, folderPath), onExit = { deck = null })
        else -> SetupScreen(
            all = all,
            filter = filter,
            onFilter = { filter = it },
            needsFilesAccess = needsFilesAccess,
            folder = folderPath?.let { FolderPick(it, folderItems) },
            onPickFolder = {
                if (filesGranted) picker.launch(null)
                else {
                    Toast.makeText(ctx, "Primero activa \"Acceso a todos los archivos\" para Tachito", Toast.LENGTH_LONG).show()
                    ctx.openFilesAccessSettings()
                }
            },
            onClearFolder = { folderPath = null; folderItems = null },
            trash = trash,
            onOpenTrash = { trashOpen = true },
            onStart = { deck = it },
        )
    }
}

@Composable
fun PermissionScreen(asked: Boolean, onRequest: () -> Unit) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Logo(72)
        Spacer(Modifier.height(16.dp))
        Text("Tachito", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text("Para revisar tus fotos y videos necesito permiso para verlos. Nada sale de tu teléfono.", textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("Dar permiso") }
        if (asked) TextButton(onClick = { ctx.openAppSettings() }) { Text("Abrir ajustes de la app") }
    }
}

private fun Context.openAppSettings() =
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))

fun Context.openFilesAccessSettings() =
    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", packageName, null)))

/** Clave para retomar cada combinación de filtros donde se quedó. En orden aleatorio no tiene sentido. */
private fun resumeKey(f: Filter, folder: String?) =
    if (f.order == Order.RANDOM) null
    else listOf("pos", f.files, f.kind, f.order, folder, f.sources.map { it.toString() }.sorted()).joinToString("|")

@Composable
fun SwipeScreen(items: List<Media>, resumeKey: String?, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Se guarda el id del siguiente por revisar (no se borra al decidir), no la posición: lo enviado a la papelera desplaza la lista.
    val resumeAt = remember {
        val id = resumeKey?.let { ctx.prefs().getLong(it, 0L) }
        items.indexOfFirst { it.id == id }.coerceAtLeast(0)
    }
    var index by remember { mutableIntStateOf(resumeAt) }
    LaunchedEffect(Unit) {
        if (resumeAt > 0) Toast.makeText(ctx, "Retomando donde te quedaste (${resumeAt + 1} de ${items.size})", Toast.LENGTH_SHORT).show()
    }
    LaunchedEffect(index) {
        val key = resumeKey ?: return@LaunchedEffect
        val next = items.getOrNull(index)
        ctx.prefs().edit().apply { if (next == null) remove(key) else putLong(key, next.id) }.apply()
    }
    val history = remember { mutableStateListOf<Pair<Media, Boolean>>() } // (media, borrar)
    val pending = remember { mutableStateListOf<Media>() }
    var freed by remember { mutableLongStateOf(0L) }
    var muted by remember { mutableStateOf(true) }
    var askExit by remember { mutableStateOf(false) }
    var exitAfterTrash by remember { mutableStateOf(false) }
    var width by remember { mutableIntStateOf(1) }

    fun trashed(done: List<Media>) {
        val bytes = done.sumOf { it.size }
        freed += bytes
        val prefs = ctx.prefs()
        prefs.edit().putLong("freed", prefs.getLong("freed", 0) + bytes).apply()
        pending.removeAll(done)
        history.clear() // lo ya enviado a la papelera no se puede deshacer aquí
    }

    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            trashed(pending.filter { it.isMediaItem })
            if (exitAfterTrash) onExit()
        }
        exitAfterTrash = false
    }

    fun sendToTrash() {
        // Archivos de "Elegir otra carpeta": papelera propia (la del sistema no los acepta)
        val own = pending.filter { it.file != null }
        if (own.isNotEmpty()) trashed(moveToOwnTrash(ctx, own))
        val (media, others) = pending.filter { it.file == null }.partition { it.isMediaItem }
        // createTrashRequest solo acepta fotos/videos/audio. Lo demás se marca directo
        // (posible gracias al acceso a todos los archivos); el toque en "Enviar" es la confirmación.
        val done = others.filter {
            runCatching {
                ctx.contentResolver.update(it.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }, null) > 0
            }.getOrDefault(false)
        }
        trashed(done)
        if (done.size < others.size) {
            Toast.makeText(ctx, "No se pudieron mover ${others.size - done.size} archivos", Toast.LENGTH_SHORT).show()
        }
        if (media.isNotEmpty()) {
            // ponytail: una sola solicitud para todo el lote; si alguien marca miles de golpe y falla, dividir en tandas.
            val pi = MediaStore.createTrashRequest(ctx.contentResolver, media.map { it.uri }, true)
            trashLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } else {
            if (exitAfterTrash && pending.isEmpty()) onExit()
            exitAfterTrash = false
        }
    }

    fun decide(delete: Boolean) {
        val m = items.getOrNull(index) ?: return
        history += m to delete
        if (delete) pending += m
        index++
    }

    fun undo() {
        val (m, deleted) = history.removeAt(history.lastIndex)
        if (deleted) pending.remove(m)
        index = items.indexOf(m) // tras saltar con la barra, index-- no volvería a la anterior
    }

    val current = items.getOrNull(index)
    val offset = remember(current?.id) { Animatable(0f) }

    fun fling(delete: Boolean) {
        if (offset.isRunning && abs(offset.targetValue) > width) return
        scope.launch {
            offset.animateTo(if (delete) -width * 1.5f else width * 1.5f, tween(220))
            decide(delete)
        }
    }

    BackHandler {
        if (pending.isNotEmpty()) askExit = true else onExit()
    }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (pending.isNotEmpty()) askExit = true else onExit() }) {
                Icon(Icons.Default.ArrowBack, "Volver")
            }
            Text("${minOf(index + 1, items.size)} / ${items.size}", Modifier.weight(1f))
            Text("Liberado: ${ctx.fmt(freed)}", color = GREEN, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
        }
        // Barra para saltar a cualquier punto del total
        if (items.size > 1) Slider(
            value = minOf(index, items.lastIndex).toFloat(),
            onValueChange = { index = it.roundToInt() },
            valueRange = 0f..items.lastIndex.toFloat(),
            modifier = Modifier.padding(horizontal = 16.dp).height(24.dp),
        )

        Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp).onSizeChanged { width = it.width }) {
            if (current == null) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("¡Revisaste todo!", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { if (pending.isNotEmpty()) askExit = true else onExit() }) { Text("Volver al menú") }
                }
            } else {
                items.getOrNull(index + 1)?.let { next ->
                    key(next.id) {
                        AnyCard(next, active = false, muted = true, onToggleMute = {}, Modifier.graphicsLayer { scaleX = 0.95f; scaleY = 0.95f })
                    }
                }
                key(current.id) {
                    val progress = offset.value / (width * 0.3f)
                    Box(
                        Modifier
                            .graphicsLayer { translationX = offset.value; rotationZ = offset.value / 40f }
                            .pointerInput(current.id) {
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        if (abs(offset.value) > size.width * 0.3f) fling(offset.value < 0)
                                        else scope.launch { offset.animateTo(0f) }
                                    },
                                    onDragCancel = { scope.launch { offset.animateTo(0f) } },
                                ) { change, amount ->
                                    change.consume()
                                    scope.launch { offset.snapTo(offset.value + amount) }
                                }
                            },
                    ) {
                        AnyCard(current, active = true, muted = muted, onToggleMute = { muted = !muted })
                        // Sello centrado con icono y tinte: en los costados se cortaba o confundía el lado
                        Stamp(Icons.Default.Delete, "BORRAR", RED, (-progress).coerceIn(0f, 1f), Modifier.align(Alignment.TopCenter))
                        Stamp(Icons.Default.Check, "CONSERVAR", GREEN, progress.coerceIn(0f, 1f), Modifier.align(Alignment.TopCenter))
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top,
        ) {
            // Mismo orden que Tinder: deshacer, descartar, conservar
            ActionButton(AppIcons.Undo, "Deshacer", MaterialTheme.colorScheme.onSurfaceVariant, 56, enabled = history.isNotEmpty()) { undo() }
            ActionButton(Icons.Default.Delete, "Borrar", RED, 72, enabled = current != null) { fling(true) }
            ActionButton(Icons.Default.Check, "Conservar", GREEN, 72, enabled = current != null) { fling(false) }
        }

        if (pending.isNotEmpty()) {
            Button(
                onClick = ::sendToTrash,
                colors = ButtonDefaults.buttonColors(containerColor = RED),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(52.dp),
            ) {
                Icon(Icons.Default.Delete, null)
                Spacer(Modifier.width(8.dp))
                Text("Enviar ${pending.size} a la papelera · ${ctx.fmt(pending.sumOf { it.size })}")
            }
        }
    }

    if (askExit) AlertDialog(
        onDismissRequest = { askExit = false },
        icon = { Icon(Icons.Default.Delete, null) },
        title = { Text("¿Enviar a la papelera?") },
        text = {
            Text("Marcaste ${pending.size} elementos (${ctx.fmt(pending.sumOf { it.size })}). Si sales sin confirmar no se borra nada.")
        },
        confirmButton = {
            TextButton(onClick = { askExit = false; exitAfterTrash = true; sendToTrash() }) { Text("Enviar") }
        },
        dismissButton = {
            TextButton(onClick = { askExit = false; onExit() }) { Text("Salir sin borrar") }
        },
    )
}

@Composable
private fun Stamp(icon: ImageVector, text: String, color: Color, alpha: Float, modifier: Modifier) {
    if (alpha <= 0f) return
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(color.copy(alpha = 0.25f * alpha)))
    Row(
        modifier
            .padding(20.dp)
            .graphicsLayer { this.alpha = alpha }
            .background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(28.dp), tint = color)
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontSize = 26.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, color: Color, sizeDp: Int, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Caja fija de 72dp para que las etiquetas queden alineadas aunque el botón sea más chico
        Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
            FilledTonalIconButton(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.size(sizeDp.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(contentColor = color),
            ) {
                Icon(icon, label, Modifier.size((sizeDp / 2).dp))
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AnyCard(m: Media, active: Boolean, muted: Boolean, onToggleMute: () -> Unit, modifier: Modifier = Modifier) {
    if (m.isImage || m.isVideo) MediaCard(m, active, muted, onToggleMute, modifier) else FileCard(m, active, modifier)
}

@Composable
private fun InfoOverlay(m: Media, title: String, modifier: Modifier) {
    val ctx = LocalContext.current
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
            .padding(16.dp),
    ) {
        Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        val extra = if (m.durationMs > 0) " · " + DateUtils.formatElapsedTime(m.durationMs / 1000) else ""
        Text(
            "${m.album} · ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(m.date))}$extra",
            color = Color.White.copy(alpha = 0.8f),
        )
    }
}

@Composable
fun MediaCard(m: Media, active: Boolean, muted: Boolean, onToggleMute: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Box(modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(Color.Black)) {
        Thumbnail(m, Modifier.fillMaxSize())
        if (m.isVideo && active) VideoPlayer(m.uri, muted)
        InfoOverlay(m, ctx.fmt(m.size), Modifier.align(Alignment.BottomStart))
        if (m.isImage && active) {
            var zoom by remember(m.id) { mutableStateOf(false) }
            FilledTonalIconButton(onClick = { zoom = true }, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(52.dp)) {
                Icon(AppIcons.ZoomIn, "Ver con zoom")
            }
            if (zoom) ZoomViewer(m.name, 1, { zoom = false }) { loadBitmap(ctx, m, 4096)?.asImageBitmap() }
        }
        if (m.isVideo && active) {
            FilledTonalIconButton(onClick = onToggleMute, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(52.dp)) {
                Icon(if (muted) AppIcons.VolumeOff else AppIcons.VolumeUp, if (muted) "Activar sonido" else "Silenciar")
            }
        }
    }
}

@Composable
private fun FileCard(m: Media, active: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val icon = when (m.kind) {
        Kind.AUDIO -> AppIcons.Music
        Kind.DOCS -> AppIcons.Doc
        else -> AppIcons.File
    }
    val pdf = remember(m.id) { if (m.mime == "application/pdf") Pdf.open(ctx, m) else null }
    DisposableEffect(pdf) { onDispose { pdf?.close() } }
    val preview by produceState<ImageBitmap?>(null, pdf) { value = withContext(Dispatchers.IO) { pdf?.render(0, 1000) } }
    var viewing by remember(m.id) { mutableStateOf(false) }
    if (viewing && pdf != null) ZoomViewer(m.name, pdf.pages, { viewing = false }) { pdf.render(it, 2400) }

    Box(modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            Modifier.align(Alignment.Center).padding(24.dp).padding(bottom = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            preview?.let { Image(it, null, Modifier.heightIn(max = 300.dp).clip(RoundedCornerShape(8.dp))) }
                ?: Icon(icon, null, Modifier.size(120.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(
                m.name,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Text(m.kind.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            if (active) when {
                pdf != null -> FilledTonalButton(onClick = { viewing = true }) {
                    Icon(AppIcons.ZoomIn, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Ver" + if (pdf.pages > 1) " · ${pdf.pages} págs." else "")
                }
                m.kind == Kind.AUDIO -> AudioButton(m.uri)
                m.kind == Kind.APKS -> {}
                else -> FilledTonalButton(onClick = {
                    runCatching {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW).setDataAndType(
                                m.file?.let { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", File(it)) } ?: m.uri,
                                m.mime,
                            ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        )
                    }.onFailure { Toast.makeText(ctx, "No hay una app para abrir este archivo", Toast.LENGTH_SHORT).show() }
                }) {
                    Icon(AppIcons.Open, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Abrir")
                }
            }
        }
        InfoOverlay(m, ctx.fmt(m.size), Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun AudioButton(uri: Uri) {
    val ctx = LocalContext.current
    val player = remember(uri) { MediaPlayer() }
    var prepared by remember(uri) { mutableStateOf(false) }
    var playing by remember(uri) { mutableStateOf(false) }
    DisposableEffect(player) { onDispose { player.release() } }
    OnLifecycle { if (it == Lifecycle.Event.ON_PAUSE && playing) runCatching { player.pause(); playing = false } }

    FilledTonalButton(onClick = {
        runCatching {
            if (!prepared) {
                player.setDataSource(ctx, uri)
                player.setOnCompletionListener { playing = false }
                player.prepare()
                prepared = true
            }
            if (playing) player.pause() else player.start()
            playing = !playing
        }.onFailure { Toast.makeText(ctx, "No se pudo reproducir", Toast.LENGTH_SHORT).show() }
    }) {
        Icon(if (playing) AppIcons.Pause else Icons.Default.PlayArrow, null)
        Spacer(Modifier.width(8.dp))
        Text(if (playing) "Pausar" else "Escuchar")
    }
}

@Composable
fun Thumbnail(m: Media, modifier: Modifier, maxSide: Int = 1600, crop: Boolean = false) {
    val ctx = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, m.id) {
        value = withContext(Dispatchers.IO) { loadBitmap(ctx, m, maxSide)?.asImageBitmap() }
    }
    Box(modifier) {
        bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = if (crop) ContentScale.Crop else ContentScale.Fit) }
            ?: CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
    }
}

@Composable
private fun VideoPlayer(uri: Uri, muted: Boolean) {
    val player = remember(uri) { MediaPlayer() }
    var ratio by remember(uri) { mutableFloatStateOf(0f) }
    val mutedNow by rememberUpdatedState(muted)

    DisposableEffect(player) { onDispose { player.release() } }
    OnLifecycle {
        runCatching {
            if (it == Lifecycle.Event.ON_PAUSE) player.pause()
            if (it == Lifecycle.Event.ON_RESUME && ratio > 0f) player.start()
        }
    }
    LaunchedEffect(player, muted) {
        runCatching { val v = if (muted) 0f else 1f; player.setVolume(v, v) }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            runCatching {
                                player.setDataSource(ctx, uri)
                                player.setSurface(Surface(st))
                                player.isLooping = true
                                player.setOnVideoSizeChangedListener { _, vw, vh -> if (vw > 0 && vh > 0) ratio = vw.toFloat() / vh }
                                player.setOnPreparedListener {
                                    val v = if (mutedNow) 0f else 1f
                                    it.setVolume(v, v)
                                    it.start()
                                }
                                player.setOnErrorListener { _, _, _ -> true }
                                player.prepareAsync()
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            },
            modifier = if (ratio > 0f) Modifier.aspectRatio(ratio) else Modifier.size(1.dp),
        )
    }
}
