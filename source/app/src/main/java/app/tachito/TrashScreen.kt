package app.tachito

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Un elemento de la papelera: del sistema (own = null) o de la papelera propia de Tachito. */
data class TrashRow(val media: Media, val own: TrashBin.Item? = null, val bin: TrashBin? = null) {
    val key get() = (if (own != null) "o" else "s") + media.id
}

fun loadTrash(ctx: Context): List<TrashRow> {
    val system = runCatching { querySystemTrash(ctx.contentResolver) }.getOrDefault(emptyList()).map { TrashRow(it) }
    val mimes = MimeTypeMap.getSingleton()
    val own = allBins(ctx).flatMap { bin ->
        runCatching { bin.items() }.getOrDefault(emptyList()).map { item ->
            val original = File(item.original)
            TrashRow(
                Media(
                    id = item.file.path.hashCode().toLong(),
                    mime = mimes.getMimeTypeFromExtension(original.extension.lowercase()) ?: "application/octet-stream",
                    size = item.file.length(),
                    date = item.trashedAt,
                    album = original.parentFile?.name ?: "",
                    path = original.parent ?: "",
                    name = original.name,
                    file = item.file.path,
                    expires = item.expiresAt,
                ),
                item,
                bin,
            )
        }
    }
    return (system + own).sortedByDescending { it.media.expires }
}

private fun daysLeft(expires: Long): Long = maxOf(0, (expires - System.currentTimeMillis()) / (24 * 60 * 60 * 1000L))

@Composable
fun TrashScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<TrashRow>?>(null) }
    var confirmDelete by remember { mutableStateOf<List<TrashRow>?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        rows = withContext(Dispatchers.IO) { loadTrash(ctx) }
        selected = emptySet()
    }
    BackHandler(onBack = onBack)

    // Diálogos del sistema para fotos/videos/audio (restaurar o borrar definitivo)
    val systemLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) reload++
    }

    /** Restaura varios a la vez: un solo diálogo del sistema para fotos/videos/audio. */
    fun restore(all: List<TrashRow>) = scope.launch {
        val (own, system) = all.partition { it.own != null }
        val (media, others) = system.map { it.media }.partition { it.isMediaItem }
        val failed = withContext(Dispatchers.IO) {
            val notRestored = own.filterNot { it.bin!!.restore(it.own!!) }
            rescan(ctx, (own - notRestored.toSet()).map { it.own!!.original })
            val extras = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
            others.forEach {
                runCatching { ctx.contentResolver.update(it.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 0) }, extras) }
            }
            notRestored.size
        }
        if (failed > 0) Toast.makeText(ctx, "$failed no se restauraron: ya existe un archivo con ese nombre en la carpeta original", Toast.LENGTH_LONG).show()
        if (media.isNotEmpty()) {
            systemLauncher.launch(
                IntentSenderRequest.Builder(MediaStore.createTrashRequest(ctx.contentResolver, media.map { it.uri }, false).intentSender).build()
            )
        } else reload++
    }

    fun deleteForever(all: List<TrashRow>) = scope.launch {
        val (own, system) = all.partition { it.own != null }
        val (media, others) = system.map { it.media }.partition { it.isMediaItem }
        withContext(Dispatchers.IO) {
            own.groupBy { it.bin!! }.forEach { (bin, l) -> bin.delete(l.map { it.own!! }) }
            deleteNonMediaFromSystemTrash(ctx.contentResolver, others)
        }
        if (media.isNotEmpty()) {
            // ponytail: una sola solicitud; con miles de elementos podría hacer falta dividir en tandas.
            systemLauncher.launch(IntentSenderRequest.Builder(MediaStore.createDeleteRequest(ctx.contentResolver, media.map { it.uri }).intentSender).build())
        } else reload++
    }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Volver") }
            Text("Papelera", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        val list = rows
        when {
            list == null -> Box(Modifier.weight(1f).fillMaxWidth()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }

            list.isEmpty() -> EmptyState(
                Icons.Default.Delete, "La papelera está vacía", "Lo que envíes a la papelera aparecerá aquí durante $TRASH_DAYS días.",
                null, {}, Modifier.weight(1f),
            )

            else -> {
                val chosen = list.filter { it.key in selected }
                Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${list.size} elementos · ${ctx.fmt(list.sumOf { it.media.size })}", fontWeight = FontWeight.SemiBold)
                        Text("Se borran solos a los $TRASH_DAYS días.", color = muted, fontSize = 13.sp)
                    }
                    TextButton(onClick = { selected = if (chosen.size == list.size) emptySet() else list.map { it.key }.toSet() }) {
                        Text(if (chosen.size == list.size) "Ninguno" else "Todos")
                    }
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                    items(list, key = { it.key }) { row ->
                        TrashItem(row, row.key in selected) {
                            selected = if (row.key in selected) selected - row.key else selected + row.key
                        }
                        RowDivider()
                    }
                }
                Column {
                    RowDivider()
                    // Sin selección: vaciar todo. Con selección: restaurar o borrar solo lo marcado.
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (chosen.isNotEmpty()) OutlinedButton(
                            onClick = { restore(chosen) },
                            shape = RADIUS,
                            border = border,
                            modifier = Modifier.weight(1f).height(52.dp),
                        ) {
                            Icon(AppIcons.Undo, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Restaurar ${chosen.size}", fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            onClick = { confirmDelete = chosen.ifEmpty { list } },
                            shape = RADIUS,
                            colors = ButtonDefaults.buttonColors(containerColor = RED, contentColor = androidx.compose.ui.graphics.Color.White),
                            modifier = Modifier.weight(1f).height(52.dp),
                        ) {
                            Icon(Icons.Default.Delete, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (chosen.isEmpty()) "Vaciar papelera" else "Borrar ${chosen.size}", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }

    val toDelete = confirmDelete
    if (toDelete != null) AlertDialog(
        onDismissRequest = { confirmDelete = null },
        icon = { Icon(Icons.Default.Delete, null, tint = RED) },
        title = { Text("¿Borrar para siempre?") },
        text = { Text("Se eliminarán ${toDelete.size} elementos (${ctx.fmt(toDelete.sumOf { it.media.size })}). Esto no se puede deshacer.") },
        confirmButton = {
            TextButton(onClick = { confirmDelete = null; deleteForever(toDelete) }) { Text("Borrar", color = RED, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancelar") } },
    )
}

@Composable
private fun TrashItem(row: TrashRow, checked: Boolean, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    val m = row.media
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(RADIUS).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            if (m.isImage || m.isVideo) Thumbnail(m, Modifier.fillMaxSize(), maxSide = 200, crop = true)
            else Icon(if (m.isAudio) AppIcons.Music else AppIcons.File, null, tint = muted)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(m.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                "${ctx.fmt(m.size)} · ${m.album} · quedan ${daysLeft(m.expires)} días",
                color = muted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Checkbox(checked, { onToggle() })
    }
}
