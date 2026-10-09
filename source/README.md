# Tachito: código fuente

App nativa Android para revisar fotos, videos y archivos uno por uno y enviarlos a la papelera con un swipe.

## Qué hace

- **Dos modos**: *Fotos y videos* o *Archivos* (audio, documentos, comprimidos, APKs, otros).
- **Filtros antes de empezar**:
  - **Tipo**.
  - **Orden**: más pesados, más antiguos, más recientes o aleatorio.
  - **De dónde**: Galería (cámara), Capturas, WhatsApp, Google Fotos, Telegram, Descargas o cualquier carpeta. Se pueden marcar varias y se suman.
  - **Carpetas**: lista plegable con buscador.
- **Elegir otra carpeta…**: abre el explorador de carpetas de Android. Sirve para carpetas que la galería no muestra (con `.nomedia`, como "WhatsApp Images/Sent" o cachés).
- **Modo limpieza**: izquierda o 🗑 borra, derecha o ✓ conserva, ↶ deshace.
  - Los videos se reproducen sin sonido, con un botón para activarlo.
  - Las fotos tienen botón de zoom: visor a pantalla completa con pellizcar, doble toque o botones +/−.
  - Los PDF muestran la primera página y tienen visor propio (páginas y zoom) con `PdfRenderer`.
  - Los audios tienen botón *Escuchar* y el resto de documentos, botón *Abrir*.
- **Papelera**: nada se borra al deslizar. Lo marcado se envía junto con el botón rojo.
  - Lo de la galería va a la papelera del sistema.
  - Lo de "otra carpeta" va a la papelera propia de Tachito (`.tachito-papelera`).
  - Las dos se ven juntas en la pantalla **Papelera**, con *Restaurar* en cada elemento y *Vaciar papelera* para borrar definitivamente. Lo que no se vacía se borra solo a los 30 días.
- **Acerca de** (icono ⓘ arriba): versión y contacto del autor.
- **Contador** de espacio liberado en la sesión y en total.
- **Diseño**: estilo shadcn/ui (bordes finos, tarjetas, controles segmentados) con la paleta del logo. Tiene modo claro y oscuro.

## Requisitos

- Android 11 (API 30) o superior en el teléfono.
- Para compilar: JDK 17 y Android SDK con plataforma 34. Gradle se descarga solo con el wrapper.

## Compilar

```bash
cd source
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # ruta a tu Android SDK

./gradlew testDebugUnitTest   # tests de filtros y orden
./gradlew assembleDebug       # APK de prueba: app/build/outputs/apk/debug/
./gradlew assembleRelease     # APK firmado:  app/build/outputs/apk/release/
```

Instalar directo en un teléfono conectado por USB (con depuración USB activada):

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Firma del APK de release

El release se firma con un keystore propio que **no está en el repo**. Hace falta crear `source/keystore.properties`:

```properties
storeFile=tachito.jks
storePassword=...
keyAlias=desliza
keyPassword=...
```

Sin ese archivo, `assembleRelease` genera un APK sin firmar.

> ⚠️ Guarda una copia de `tachito.jks` y `keystore.properties` fuera del computador (por ejemplo, en un gestor de contraseñas). Si se pierden, las actualizaciones ya no se podrán instalar encima de la versión anterior y cada persona tendrá que desinstalar primero.

Para crear un keystore nuevo:

```bash
keytool -genkeypair -keystore tachito.jks -alias desliza -keyalg RSA -keysize 2048 -validity 10000
```

## Publicar una versión nueva

Automático con GitHub Actions ([`.github/workflows/build.yml`](../.github/workflows/build.yml)):

- **Pull request**: corre los tests.
- **Merge a `main`**:
  1. Corre los tests.
  2. Compila el APK firmado.
  3. Publica un [Release](https://github.com/fzzio/tachito/releases) `v1.1.<n>` con el APK adjunto como `Tachito.apk` y borra los releases anteriores (solo queda el último).
  4. El link fijo [releases/latest/download/Tachito.apk](https://github.com/fzzio/tachito/releases/latest/download/Tachito.apk) siempre baja la última versión.

El número de build `<n>` sale de la corrida de CI. Así `versionCode` siempre sube y cada APK se instala encima del anterior. Para un cambio grande, sube `baseVersion` en `app/build.gradle.kts` (por ejemplo, `"1.2"`).

La firma en CI usa estos secretos del repo:

- `KEYSTORE_BASE64`: el `.jks` en base64.
- `KEYSTORE_PASSWORD`, `KEY_ALIAS` y `KEY_PASSWORD`.

## Código

```
app/src/main/java/app/tachito/
├── MainActivity.kt   # navegación, permisos, pantalla de swipe, tarjetas y reproductores
├── Setup.kt          # pantalla principal: modo, tipo, orden, fuentes, carpetas, "elegir otra carpeta"
├── TrashScreen.kt    # pantalla Papelera: restaurar y vaciar (sistema + propia)
├── Trash.kt          # papelera propia (TrashBin), escaneo de carpetas, papelera del sistema
├── Media.kt          # modelo, consulta a MediaStore, tipos, fuentes, filtros y orden
├── Theme.kt          # paleta del logo y componentes base (Section, Segmented, PillRow, SelectRow)
└── Icons.kt          # iconos Material como paths (evita la librería de iconos extendida)
app/src/test/.../SelectTest.kt    # filtros, fuentes (también varias a la vez) y tipos de archivo
app/src/test/.../TrashBinTest.kt  # mover, restaurar sin pisar, vencimiento a 30 días, rutas de volúmenes
```

Sin librerías aparte de Compose, Material 3 y Activity:

- Las imágenes se decodifican con `ImageDecoder`.
- Los videos se reproducen con `MediaPlayer` sobre un `TextureView`.
- El borrado usa `MediaStore.createTrashRequest`.
