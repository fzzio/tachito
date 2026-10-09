package app.tachito

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Estilo tipo shadcn/ui (bordes de 1px, radios suaves, mucho aire) con la paleta del logo:
// morado del fondo, azul marino de la carita y rosa de los cachetes.
val PURPLE = Color(0xFF6D4AFF)
val NAVY = Color(0xFF2A1B6B)
val PINK = Color(0xFFFF8FB1)
val RED = Color(0xFFE5484D)
val GREEN = Color(0xFF16A34A)
val RADIUS = RoundedCornerShape(12.dp)

private val Light = lightColorScheme(
    primary = PURPLE, onPrimary = Color.White,
    secondary = PINK, onSecondary = NAVY,
    secondaryContainer = Color(0xFFF1EDFF), onSecondaryContainer = NAVY,
    background = Color.White, onBackground = Color(0xFF1B1433),
    surface = Color.White, onSurface = Color(0xFF1B1433),
    surfaceVariant = Color(0xFFF5F3FF), onSurfaceVariant = Color(0xFF6E6889),
    surfaceContainer = Color(0xFFF5F3FF), surfaceContainerHigh = Color(0xFFF1EDFF),
    surfaceContainerHighest = Color(0xFFE9E4FF), surfaceContainerLow = Color(0xFFFAF9FF),
    outline = Color(0xFFE7E3F5), outlineVariant = Color(0xFFE7E3F5),
    error = RED,
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9C86FF), onPrimary = Color(0xFF120D26),
    secondary = PINK, onSecondary = NAVY,
    secondaryContainer = Color(0xFF2A2250), onSecondaryContainer = Color(0xFFF3F0FF),
    background = Color(0xFF120D26), onBackground = Color(0xFFF3F0FF),
    surface = Color(0xFF120D26), onSurface = Color(0xFFF3F0FF),
    surfaceVariant = Color(0xFF221A42), onSurfaceVariant = Color(0xFFA9A2C8),
    surfaceContainer = Color(0xFF1A1436), surfaceContainerHigh = Color(0xFF221A42),
    surfaceContainerHighest = Color(0xFF2A2250), surfaceContainerLow = Color(0xFF1A1436),
    outline = Color(0xFF2E2652), outlineVariant = Color(0xFF2E2652),
    error = Color(0xFFFF6369),
)

@Composable
fun TachitoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}

val muted @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
val border @Composable get() = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)

/** Etiqueta pequeña + tarjeta con borde. */
@Composable
fun Section(label: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.padding(top = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = muted)
            trailing()
        }
        Column(Modifier.fillMaxWidth().border(border, RADIUS).clip(RADIUS), content = content)
    }
}

/** Control segmentado (tabs en píldora). */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RADIUS).background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (on) MaterialTheme.colorScheme.onSurface else muted,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

/** Fila de píldoras desplazable horizontalmente (una sola línea, no ocupa media pantalla). */
@Composable
fun <T> PillRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach {
            val on = it == selected
            Text(
                label(it),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .then(if (on) Modifier.background(MaterialTheme.colorScheme.primary) else Modifier.border(border, RoundedCornerShape(50)))
                    .clickable { onSelect(it) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** Selector compacto tipo <Select>: fila con valor actual y menú desplegable. */
@Composable
fun <T> SelectRow(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f))
            Text(label(selected), color = muted)
            Icon(Icons.Default.ArrowDropDown, null, tint = muted)
        }
        DropdownMenu(open, { open = false }, Modifier.align(Alignment.TopEnd)) {
            options.forEach {
                DropdownMenuItem(
                    text = { Text(label(it)) },
                    trailingIcon = { if (it == selected) Icon(Icons.Default.Check, null, Modifier.size(18.dp)) },
                    onClick = { onSelect(it); open = false },
                )
            }
        }
    }
}

/** Logo: el mismo dibujo del icono de la app sobre círculo morado. */
@Composable
fun Logo(size: Int = 44) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(50)).background(PURPLE), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(R.drawable.ic_fg), null,
            Modifier.size((size * 1.6f).dp),
        )
    }
}

@Composable
fun RowDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outline)
