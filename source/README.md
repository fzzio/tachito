# Tachito: código fuente

App nativa Android para revisar fotos, videos y archivos uno por uno y enviarlos a la papelera con un swipe.

## Qué hace

- **Dos modos**: *Fotos y videos* o *Archivos* (audio, documentos, comprimidos, APKs, otros).
- **Filtros antes de empezar**:
  - **Tipo**: según el modo.
  - **Orden**: más pesados, más antiguos, más recientes o aleatorio.
  - **Fuente**: Galería (cámara), Capturas, WhatsApp, Google Fotos, Telegram, Descargas o cualquier carpeta.
- **Modo limpieza**: izquierda o 🗑 borra, derecha o ✓ conserva, ↶ deshace.
  - Los videos se reproducen sin sonido, con un botón para activarlo.
  - Los audios tienen botón *Escuchar* y los documentos, botón *Abrir*.
- **Papelera del sistema**: nada se borra al deslizar. Lo marcado se envía junto con el botón rojo, y se puede recuperar durante 30 días.
- **Contador** de espacio liberado en la sesión y en total.

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

1. Sube `versionCode` y `versionName` en `app/build.gradle.kts`.
2. Ejecuta `./gradlew assembleRelease`.
3. Comparte `app/build/outputs/apk/release/app-release.apk`. Se instala encima de la anterior.

## Código

```
app/src/main/java/app/tachito/
├── MainActivity.kt   # toda la UI en Compose: permisos, menú de filtros, tarjetas con swipe, reproductores
├── Media.kt          # modelo, consulta a MediaStore, tipos, fuentes, filtros y orden
└── Icons.kt          # 8 iconos Material como paths (evita la librería de iconos extendida)
app/src/test/.../SelectTest.kt   # tests de filtros, fuentes y tipos de archivo
```

Sin librerías aparte de Compose, Material 3 y Activity:

- Las imágenes se decodifican con `ImageDecoder`.
- Los videos se reproducen con `MediaPlayer` sobre un `TextureView`.
- El borrado usa `MediaStore.createTrashRequest`.
