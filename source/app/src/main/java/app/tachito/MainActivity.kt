package app.tachito

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
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
import android.util.Size
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

private val RED = Color(0xFFE53935)
private val GREEN = Color(0xFF43A047)

private val PERMISSIONS =
    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

private fun hasMediaPermission(ctx: Context) =
    PERMISSIONS.any { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

private fun Context.fmt(bytes: Long) = Formatter.formatShortFileSize(this, bytes)

private fun Context.prefs() = getSharedPreferences("tachito", Context.MODE_PRIVATE)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            val scheme = when {
                Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(this)
                Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(this)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
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
    val needsFilesAccess = filter.files && !filesGranted

    // Recarga al volver al menú: lo enviado a la papelera ya no aparece.
    LaunchedEffect(deck == null, filter.files, filesGranted) {
        if (deck != null) return@LaunchedEffect
        all = null
        all = if (needsFilesAccess) emptyList() else withContext(Dispatchers.IO) { queryMedia(ctx.contentResolver, filter.files) }
    }

    val d = deck
    if (d == null) SetupScreen(all, filter, needsFilesAccess, { filter = it }, onStart = { deck = it })
    else SwipeScreen(d, onExit = { deck = null })
}

@Composable
fun PermissionScreen(asked: Boolean, onRequest: () -> Unit) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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

private fun Context.openFilesAccessSettings() =
    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", packageName, null)))

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(
    all: List<Media>?,
    filter: Filter,
    needsFilesAccess: Boolean,
    onFilter: (Filter) -> Unit,
    onStart: (List<Media>) -> Unit,
) {
    val ctx = LocalContext.current
    val totalFreed = remember { ctx.prefs().getLong("freed", 0) }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            Text("Tachito", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(
                if (totalFreed > 0) "Has liberado ${ctx.fmt(totalFreed)} en total" else "Izquierda borra, derecha conserva",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TabRow(selectedTabIndex = if (filter.files) 1 else 0, modifier = Modifier.padding(top = 12.dp)) {
            listOf("Fotos y videos", "Archivos").forEachIndexed { i, label ->
                Tab(selected = filter.files == (i == 1), onClick = { onFilter(Filter(files = i == 1, order = filter.order)) }, text = { Text(label) })
            }
        }

        when {
            needsFilesAccess -> Column(
                Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(AppIcons.Doc, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text(
                    "Para revisar audios, documentos y otros archivos, Android pide activar \"Acceso a todos los archivos\" para Tachito.",
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = { ctx.openFilesAccessSettings() }) { Text("Activar acceso") }
            }

            all == null -> Box(Modifier.weight(1f).fillMaxWidth()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }

            else -> SetupOptions(all, filter, onFilter, onStart, Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupOptions(all: List<Media>, filter: Filter, onFilter: (Filter) -> Unit, onStart: (List<Media>) -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val byKind = remember(all, filter.kind) { all.filter { it.matches(filter.kind) } }
    val presets = remember(byKind) {
        Preset.entries.map { p -> p to byKind.filter(p.test) }.filter { it.second.isNotEmpty() }
    }
    val albums = remember(byKind) {
        byKind.groupBy { it.album }.map { (name, l) -> Triple(name, l.size, l.sumOf { it.size }) }.sortedByDescending { it.third }
    }
    val selection = remember(all, filter) { all.select(filter) }
    val kinds = Kind.entries.filter { it.files == null || it.files == filter.files }

    Column(modifier) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            item {
                SectionTitle("Tipo")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    kinds.forEach { FilterChip(filter.kind == it, { onFilter(filter.copy(kind = it)) }, { Text(it.label) }) }
                }
            }
            item {
                SectionTitle("Orden")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Order.entries.forEach { FilterChip(filter.order == it, { onFilter(filter.copy(order = it)) }, { Text(it.label) }) }
                }
            }
            item { SectionTitle("Fuente (puedes marcar varias)") }
            item {
                SourceRow("Todo", byKind.size, byKind.sumOf { it.size }, filter.sources.isEmpty()) {
                    onFilter(filter.copy(sources = emptySet()))
                }
            }
            items(presets, key = { it.first.name }) { (p, l) ->
                val source = Source.Group(p)
                SourceRow(p.label, l.size, l.sumOf { it.size }, source in filter.sources) { onFilter(filter.toggle(source)) }
            }
            if (presets.any { it.first == Preset.GOOGLE_PHOTOS }) item {
                Text(
                    "Google Fotos: solo las copias guardadas en este teléfono. Lo que está únicamente en la nube no se puede borrar desde aquí.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 48.dp, end = 8.dp, bottom = 4.dp),
                )
            }
            item { SectionTitle("Carpetas") }
            items(albums, key = { "album:" + it.first }) { (name, count, bytes) ->
                val source = Source.Album(name)
                SourceRow(name, count, bytes, source in filter.sources) { onFilter(filter.toggle(source)) }
            }
        }
        Button(
            onClick = { onStart(selection) },
            enabled = selection.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
        ) {
            Text("Empezar · ${selection.size} · ${ctx.fmt(selection.sumOf { it.size })}", fontSize = 16.sp)
        }
    }
}

private fun Filter.toggle(s: Source) = copy(sources = if (s in sources) sources - s else sources + s)

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
}

@Composable
private fun SourceRow(name: String, count: Int, bytes: Long, selected: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(selected, { onClick() })
        Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("$count · ${ctx.fmt(bytes)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
fun SwipeScreen(items: List<Media>, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var index by remember { mutableIntStateOf(0) }
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
        val (media, others) = pending.partition { it.isMediaItem }
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
        index--
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
            ActionButton(Icons.Default.Delete, "Borrar", RED, 72, enabled = current != null) { fling(true) }
            ActionButton(AppIcons.Undo, "Deshacer", MaterialTheme.colorScheme.onSurfaceVariant, 56, enabled = history.isNotEmpty()) { undo() }
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
        if (m.isVideo && active) {
            FilledTonalIconButton(onClick = onToggleMute, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).size(52.dp)) {
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
    Box(modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            Modifier.align(Alignment.Center).padding(24.dp).padding(bottom = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, null, Modifier.size(120.dp), tint = MaterialTheme.colorScheme.primary)
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
            if (active) when (m.kind) {
                Kind.AUDIO -> AudioButton(m.uri)
                Kind.APKS -> {}
                else -> FilledTonalButton(onClick = {
                    runCatching {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW).setDataAndType(m.uri, m.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
private fun Thumbnail(m: Media, modifier: Modifier) {
    val ctx = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, m.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (m.isVideo) ctx.contentResolver.loadThumbnail(m.uri, Size(720, 1280), null)
                else ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, m.uri)) { decoder, info, _ ->
                    // Reduce a ~1600px de lado: suficiente para pantalla, evita cargar fotos de 50MP en memoria
                    var sample = 1
                    while (maxOf(info.size.width, info.size.height) / (sample * 2) >= 1600) sample *= 2
                    decoder.setTargetSampleSize(sample)
                }
            }.getOrNull()?.asImageBitmap()
        }
    }
    Box(modifier) {
        bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
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
