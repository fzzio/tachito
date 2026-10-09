# Decisiones técnicas

## Objetivo

Una alternativa propia, liviana y sin anuncios a las apps de "swipe para borrar fotos". Se distribuye como APK a un grupo pequeño de personas, no por la Play Store.

## Stack

| Decisión | Motivo |
|---|---|
| Kotlin + Jetpack Compose nativo | El APK pesa ~2 MB y tiene acceso directo a MediaStore. No necesita un puente como React Native. |
| Sin librerías de imágenes ni video | `ImageDecoder` (reduce la foto a ~1600 px) y `MediaPlayer` + `TextureView` cubren el caso. El `TextureView` permite rotar la tarjeta mientras el video se reproduce, cosa que `SurfaceView` no hace. |
| Iconos como paths propios | `material-icons-extended` es enorme y solo se usan 8 iconos. |
| `minSdk 30` (Android 11) | Es la versión que trae la papelera del sistema (`createTrashRequest`) y la confirmación por lote. |
| Orientación vertical fija | Evita perder el progreso al girar el teléfono. |

## Google Fotos

- **Google Fotos no tiene API para borrar.** La Library API nunca permitió borrar, y desde marzo de 2025 solo ve el contenido subido por la propia app. Iniciar sesión con Google no lo resuelve.
- **La fuente "Google Fotos" de la app** muestra los archivos *locales* que creó la app Google Fotos. Se reconocen por `OWNER_PACKAGE_NAME = com.google.android.apps.photos` o porque la ruta contiene "Google Photos".
- **Al borrar en el teléfono** se libera espacio local. La copia en la nube, si existe, se mantiene.

## Borrado

- **Al deslizar no se borra nada.** Las decisiones se acumulan en memoria y se envían juntas, porque Android exige confirmar cada operación y un solo diálogo para todo el lote es más cómodo.
- **Fotos, videos y audio** van por `MediaStore.createTrashRequest`. El sistema muestra su diálogo y los archivos quedan 30 días en la papelera.
- **Documentos, comprimidos y otros:** `createTrashRequest` los rechaza ("All requested items must be Media items"). Con el permiso de acceso a todos los archivos se marcan con `IS_TRASHED = 1` directamente. Quedan como `.trashed-<fecha>-<nombre>` y el sistema los purga a los 30 días.
- **Deshacer** funciona hasta que se confirma el envío. Después de confirmar, la recuperación es desde la papelera del sistema.

## Permisos

| Permiso | Para qué |
|---|---|
| `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` (o `READ_EXTERNAL_STORAGE` en Android 11-12) | Modo fotos y videos. |
| `MANAGE_EXTERNAL_STORAGE` | Solo para el modo Archivos. Android 13+ no da otra forma de ver documentos creados por otras apps. La Play Store restringe este permiso, pero en un APK propio no hay problema. |
| `<queries>` para Google Fotos, WhatsApp y Telegram | Para poder leer qué app creó cada archivo. |

La app no pide permiso de internet.

## Límites conocidos

- **Biblioteca completa en memoria:** se carga en una lista. Con decenas de miles de elementos sigue siendo fluido, pero si se nota lento, se puede paginar.
- **Envío a la papelera en una sola solicitud:** si alguien marca miles de elementos a la vez y falla, hay que dividir en tandas (está marcado con `ponytail:` en el código).
- **Fuente Google Fotos:** no se probó con un teléfono real que tenga archivos creados por esa app.
