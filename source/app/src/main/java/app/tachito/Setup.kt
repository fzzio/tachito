package app.tachito

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val FOLDERS_PREVIEW = 5

/** Carpeta elegida con el explorador del sistema; items = null mientras se recorre. */
data class FolderPick(val path: String, val items: List<Media>?)

@Composable
fun SetupScreen(
    all: List<Media>?,
    filter: Filter,
    onFilter: (Filter) -> Unit,
    needsFilesAccess: Boolean,
    folder: FolderPick?,
    onPickFolder: () -> Unit,
    onClearFolder: () -> Unit,
    trash: Pair<Int, Long>,
    onOpenTrash: () -> Unit,
    onStart: (List<Media>) -> Unit,
) {
    val ctx = LocalContext.current
    val totalFreed = remember { ctx.prefs().getLong("freed", 0) }
    val source = folder?.items ?: all
    val selection = remember(source, filter, folder) {
        source?.select(if (folder != null) filter.copy(sources = emptySet()) else filter).orEmpty()
    }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        // Encabezado
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Tachito", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (totalFreed > 0) "Has liberado ${ctx.fmt(totalFreed)} en total" else "← borra · conserva →",
                    color = muted,
                    fontSize = 14.sp,
                )
            }
            var about by remember { mutableStateOf(false) }
            HeaderButton({ about = true }) { Icon(AppIcons.Info, "Acerca de") }
            Spacer(Modifier.width(8.dp))
            HeaderButton(onOpenTrash) {
                BadgedBox(badge = { if (trash.first > 0) Badge(containerColor = RED) { Text(if (trash.first > 99) "99+" else "${trash.first}") } }) {
                    Icon(Icons.Default.Delete, "Papelera")
                }
            }
            if (about) AboutDialog { about = false }
        }

        Segmented(
            listOf("Fotos y videos", "Archivos"),
            selected = if (filter.files) 1 else 0,
            onSelect = { onFilter(Filter(files = it == 1, order = filter.order)) },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )

        when {
            needsFilesAccess -> EmptyState(
                AppIcons.Doc,
                "Activa el acceso a archivos",
                "Para revisar audios, documentos y otros archivos, Android pide activar \"Acceso a todos los archivos\" para Tachito.",
                "Activar acceso",
                { ctx.openFilesAccessSettings() },
                Modifier.weight(1f),
            )

            source == null -> Box(Modifier.weight(1f).fillMaxWidth()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }

            else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
                item { WhatSection(filter, onFilter) }
                item {
                    if (folder != null) FolderSection(folder, onClearFolder)
                    else SourcesSection(all.orEmpty(), filter, onFilter, onPickFolder)
                }
            }
        }

        // Barra inferior fija
        if (!needsFilesAccess) Column(Modifier.fillMaxWidth()) {
            RowDivider()
            Button(
                onClick = { onStart(selection) },
                enabled = selection.isNotEmpty(),
                shape = RADIUS,
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
            ) {
                Text(
                    if (selection.isEmpty()) "Nada que revisar" else "Empezar · ${selection.size} · ${ctx.fmt(selection.sumOf { it.size })}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun HeaderButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(RADIUS).border(border, RADIUS).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

private const val AUTHOR = "Fabricio Orrala"
private const val EMAIL = "fabricio.orrala@gmail.com"

@Composable
private fun AboutDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val version = remember { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
    AlertDialog(
        onDismissRequest = onClose,
        icon = { Logo(56) },
        title = { Text("Tachito $version") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("Libera espacio deslizando: ← borra, conserva →. Sin anuncios y sin internet.", textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Text("Hecho por", color = muted, fontSize = 13.sp)
                Text(AUTHOR, fontWeight = FontWeight.SemiBold)
                Text(
                    EMAIL,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$EMAIL")).putExtra(Intent.EXTRA_SUBJECT, "Tachito")) }
                    }.padding(4.dp),
                )
            }
        },
        confirmButton = { TextButton(onClose) { Text("Cerrar") } },
    )
}

@Composable
private fun WhatSection(filter: Filter, onFilter: (Filter) -> Unit) {
    Section("Qué revisar") {
        PillRow(Kind.entries.filter { it.files == null || it.files == filter.files }, filter.kind, { it.label }) {
            onFilter(filter.copy(kind = it))
        }
        RowDivider()
        SelectRow("Orden", Order.entries, filter.order, { it.label }) { onFilter(filter.copy(order = it)) }
    }
}

@Composable
private fun SourcesSection(all: List<Media>, filter: Filter, onFilter: (Filter) -> Unit, onPickFolder: () -> Unit) {
    val byKind = remember(all, filter.kind) { all.filter { it.matches(filter.kind) } }
    val presets = remember(byKind) { Preset.entries.map { p -> p to byKind.filter(p.test) }.filter { it.second.isNotEmpty() } }
    val albums = remember(byKind) {
        byKind.groupBy { it.album }.map { (name, l) -> Triple(name, l.size, l.sumOf { it.size }) }.sortedByDescending { it.third }
    }

    Section(
        "De dónde",
        trailing = {
            if (filter.sources.isNotEmpty()) Text(
                "Limpiar (${filter.sources.size})",
                color = muted,
                fontSize = 13.sp,
                modifier = Modifier.clip(RADIUS).clickable { onFilter(filter.copy(sources = emptySet())) }.padding(4.dp),
            )
        },
    ) {
        CheckRow("Todo", "${byKind.size} · ${LocalContext.current.fmt(byKind.sumOf { it.size })}", filter.sources.isEmpty()) {
            onFilter(filter.copy(sources = emptySet()))
        }
        presets.forEach { (p, l) ->
            RowDivider()
            val s = Source.Group(p)
            CheckRow(p.label, "${l.size} · ${LocalContext.current.fmt(l.sumOf { it.size })}", s in filter.sources) { onFilter(filter.toggle(s)) }
        }
    }
    if (presets.any { it.first == Preset.GOOGLE_PHOTOS }) Text(
        "Google Fotos: solo copias guardadas en este teléfono; la nube no se toca.",
        fontSize = 12.sp,
        color = muted,
        modifier = Modifier.padding(top = 6.dp, start = 4.dp),
    )

    FoldersSection(albums, filter, onFilter, onPickFolder)
}

@Composable
private fun FoldersSection(albums: List<Triple<String, Int, Long>>, filter: Filter, onFilter: (Filter) -> Unit, onPickFolder: () -> Unit) {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val shown = when {
        query.isNotBlank() -> albums.filter { it.first.contains(query.trim(), ignoreCase = true) }
        expanded -> albums
        // Siempre mostrar las marcadas aunque no estén entre las más pesadas
        else -> albums.take(FOLDERS_PREVIEW) + albums.drop(FOLDERS_PREVIEW).filter { Source.Album(it.first) in filter.sources }
    }

    Section("Carpetas", trailing = { Text("${albums.size}", color = muted, fontSize = 13.sp) }) {
        if (expanded || albums.size > FOLDERS_PREVIEW) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null, tint = muted, modifier = Modifier.size(20.dp))
                TextField(
                    query,
                    { query = it },
                    placeholder = { Text("Buscar carpeta", color = muted) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedIndicatorColor = MaterialTheme.colorScheme.surface,
                        unfocusedIndicatorColor = MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            RowDivider()
        }
        shown.forEach { (name, count, bytes) ->
            val s = Source.Album(name)
            CheckRow(name, "$count · ${ctx.fmt(bytes)}", s in filter.sources) { onFilter(filter.toggle(s)) }
            RowDivider()
        }
        if (query.isBlank() && albums.size > FOLDERS_PREVIEW) {
            ActionRow(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                if (expanded) "Ver menos" else "Ver todas (${albums.size})",
            ) { expanded = !expanded }
            RowDivider()
        }
        ActionRow(AppIcons.Folder, "Elegir otra carpeta…", "Incluye carpetas que la galería no muestra", onPickFolder)
    }
}

@Composable
private fun FolderSection(folder: FolderPick, onClear: () -> Unit) {
    val ctx = LocalContext.current
    Section("De dónde") {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Folder, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(folder.path.substringAfterLast('/'), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(folder.path, color = muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                folder.items?.let { Text("${it.size} archivos · ${ctx.fmt(it.sumOf { m -> m.size })}", color = muted, fontSize = 12.sp) }
            }
            TextButton(onClick = onClear) { Text("Quitar") }
        }
    }
    Text(
        "Lo que borres aquí va a la papelera de Tachito (30 días para recuperar).",
        fontSize = 12.sp,
        color = muted,
        modifier = Modifier.padding(top = 6.dp, start = 4.dp),
    )
}

@Composable
private fun CheckRow(title: String, detail: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .then(
                    if (checked) Modifier.background(MaterialTheme.colorScheme.primary)
                    else Modifier.border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(6.dp))
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Icons.Default.Check, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.width(12.dp))
        Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(detail, color = muted, fontSize = 13.sp)
    }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, detail: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = muted)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.Medium)
            detail?.let { Text(it, color = muted, fontSize = 12.sp) }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, action: String?, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(64.dp).clip(RADIUS).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(32.dp), tint = muted)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Spacer(Modifier.height(6.dp))
        Text(text, color = muted, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction, shape = RADIUS) { Text(action) }
        }
    }
}

private fun Filter.toggle(s: Source) = copy(sources = if (s in sources) sources - s else sources + s)
