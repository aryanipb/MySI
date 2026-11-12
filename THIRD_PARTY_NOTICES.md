# Third-party notices

MySI is built on open-source libraries and models. This file lists what ships inside the app,
where it comes from and under which licence. Sections are added as features land.

## Libraries

Every library in the release build, with the exact version Gradle resolves
(`./gradlew :app:dependencies --configuration releaseRuntimeClasspath`, checked 12 November 2026
for MySI 0.8). Each licence was read from the artifact's own POM (or, where the POM names none,
its parent POM, as noted), from Google Maven or Maven Central, not from memory. The release build
is shrunk by R8, so code the app never uses is removed from the APK; the libraries are listed
whether or not R8 keeps any of their code.

### Libraries MySI uses directly

| Library | Version | Licence | Source |
|---|---|---|---|
| LiteRT-LM for Android (`com.google.ai.edge.litertlm:litertlm-android`), the on-device model runtime, with its native library `liblitertlm_jni.so` | 0.18.1 | Apache-2.0 | https://github.com/google-ai-edge/LiteRT-LM |
| Kotlin standard library (`org.jetbrains.kotlin:kotlin-stdlib`) | 2.4.0 | Apache-2.0 | https://kotlinlang.org |
| Kotlin reflection (`org.jetbrains.kotlin:kotlin-reflect`, pulled in by LiteRT-LM) | 2.4.0 | Apache-2.0 | https://kotlinlang.org |
| kotlinx.coroutines (`org.jetbrains.kotlinx:kotlinx-coroutines-android`, `-core`, `-core-jvm`) | 1.11.0 | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| kotlinx atomicfu (`org.jetbrains.kotlinx:atomicfu`, `atomicfu-jvm`, used by coroutines) | 0.28.0 | Apache-2.0 | https://github.com/Kotlin/kotlinx-atomicfu |
| AndroidX Core (`androidx.core:core`, `core-ktx`) | 1.17.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/core |
| Gson (`com.google.code.gson:gson`) | 2.14.0 | Apache-2.0 | https://github.com/google/gson |
| PdfBox-Android (`com.tom-roush:pdfbox-android`), with the data files it bundles listed below | 2.0.27.0 | Apache-2.0 | https://github.com/TomRoush/PdfBox-Android |
| MediaPipe Tasks Vision, Audio and Core (`com.google.mediapipe:tasks-vision`, `tasks-audio`, `tasks-core`), with the native library `libmediapipe_tasks_jni.so` | 0.10.35 | Apache-2.0 | https://github.com/google-ai-edge/mediapipe |
| CameraX core, camera2, lifecycle, view (`androidx.camera:*`), with the native libraries `libimage_processing_util_jni.so` and `libsurface_util_jni.so` | 1.6.2 | Apache-2.0 (camera-core's POM also lists BSD-3-Clause) | https://developer.android.com/jetpack/androidx/releases/camera |

LiteRT-LM's native library is built from many open-source components. The LiteRT-LM 0.18.1 AAR
lists them, with each one's full licence text, in its `THIRD_PARTY_NOTICE.txt` (about 41,000
lines), which those licences require to accompany the app: among them Abseil, BoringSSL, Brotli,
Eigen 3 (MPL-2.0), FlatBuffers, Halide, HighwayHash, ICU4C, KleidiAI, LLVM, OpenCV, Protocol
Buffers, RE2, SwiftShader, TCMalloc, TensorFlow, Tink, XNNPACK and Zstandard, under Apache-2.0,
BSD, MIT, MPL-2.0 and similar terms. That file is not yet copied into this repository or the
app; it should ship with the app or be linked from it before release.

### Libraries those bring in

| Library | Version | Licence |
|---|---|---|
| AndroidX: `activity` 1.0.0, `annotation` and `annotation-jvm` 1.8.1, `annotation-experimental` 1.4.1, `appcompat` and `appcompat-resources` 1.1.0, `arch.core:core-common` and `core-runtime` 2.2.0, `collection` and `collection-jvm` 1.4.2, `concurrent-futures` and `concurrent-futures-ktx` 1.1.0, `core-backported-fixes` 1.0.0, `core-viewtree` 1.0.0, `cursoradapter` 1.0.0, `customview` 1.0.0, `drawerlayout` 1.0.0, `exifinterface` 1.4.2, `fragment` 1.1.0, `interpolator` 1.0.0, `lifecycle-common`, `-livedata`, `-livedata-core`, `-runtime` and `-viewmodel` 2.6.2, `loader` 1.0.0, `profileinstaller` 1.3.0, `savedstate` 1.0.0, `startup-runtime` 1.1.1, `tracing`, `tracing-android` and `tracing-ktx` 1.3.0, `vectordrawable` and `vectordrawable-animated` 1.1.0, `versionedparcelable` 1.1.1, `viewpager` 1.0.0 | see each | Apache-2.0 |
| CameraX: `camera-video` and `camera-camera2-pipe` 1.6.2, `camera.featurecombinationquery:featurecombinationquery` 1.6.2, `camera.viewfinder:viewfinder-core` 1.5.1; Media3 (used by camera-video): `media3-common`, `media3-container`, `media3-muxer` 1.9.0 | see each | Apache-2.0 |
| Google datatransport (MediaPipe's usage statistics, whose upload backend MySI unregisters): `transport-api` 3.0.0, `transport-runtime` 3.1.0, `transport-backend-cct` 3.1.0 | see each | Apache-2.0 |
| Firebase encoders (used by datatransport): `firebase-encoders` 17.0.0, `firebase-encoders-json` 18.0.0, `firebase-encoders-proto` 16.0.0 | see each | Apache-2.0 |
| Guava (`com.google.guava:guava`) | 33.3.1-android | Apache-2.0 (parent POM `guava-parent` 33.3.1-android) |
| Guava `failureaccess` 1.0.2 and `listenablefuture` 9999.0-empty-to-avoid-conflict-with-guava | see each | Apache-2.0 (parent POM `guava-parent` 26.0-android) |
| Flogger (`com.google.flogger:flogger`, `flogger-system-backend`) | 0.6 | Apache-2.0 |
| Dagger (`com.google.dagger:dagger`) | 2.59 | Apache-2.0 |
| `javax.inject:javax.inject` 1, `jakarta.inject:jakarta.inject-api` 2.0.1 | see each | Apache-2.0 |
| protobuf-javalite (`com.google.protobuf:protobuf-javalite`) | 4.26.1 | BSD-3-Clause (parent POM `protobuf-parent` 4.26.1) |
| Annotations only, which R8 removes from the release APK: `com.google.code.findbugs:jsr305` 3.0.2, `com.google.errorprone:error_prone_annotations` 2.48.0, `com.google.j2objc:j2objc-annotations` 3.0.0, `com.google.auto.value:auto-value-annotations` 1.6.3 (parent POM `auto-parent` 7), `org.jetbrains:annotations` 23.0.0, `org.jspecify:jspecify` 1.0.0 | see each | Apache-2.0 |
| Annotations only, which R8 removes from the release APK: `org.checkerframework:checker-qual` 3.43.0 | 3.43.0 | MIT |
| Annotations only, which R8 removes from the release APK: `org.checkerframework:checker-compat-qual` 2.5.3 | 2.5.3 | GPL-2.0 with the Classpath Exception, and MIT (both named in its POM) |

### Data files inside PdfBox-Android

PdfBox-Android ships these files in the APK's `assets/com/tom_roush/` folder, to lay out PDF text.
Their notices are in PdfBox-Android's `LICENSE.txt` and `NOTICE.txt` (which repeat those of Apache
PDFBox 2.0.27) and in the files themselves:

- Adobe Glyph List and Zapf Dingbats Glyph List (`glyphlist/glyphlist.txt`, `zapfdingbats.txt`),
  copyright Adobe Systems Incorporated, BSD-3-Clause.
- Adobe CMaps for PDF fonts (`fontbox/resources/cmap/`), copyright 1990-2009 Adobe Systems
  Incorporated, BSD-3-Clause.
- Adobe Font Metrics (AFM) for the 14 PDF core fonts (`afm/`), copyright Adobe Systems
  Incorporated, under Adobe's AFM licence: they may be used, copied and distributed for any
  purpose and without charge, provided all copyright notices are kept.
- Unicode Script Property (`fontbox/resources/unicode/Scripts.txt`, Scripts-10.0.0) and Bidi
  Mirroring data (`text/BidiMirroring.txt`, 8.0.0), copyright Unicode, Inc., under the Unicode
  terms of use (https://www.unicode.org/terms_of_use.html).
- Liberation Sans Regular (`ttf/LiberationSans-Regular.ttf`), digitised data copyright 2010 Google
  Corporation, copyright 2012 Red Hat, Inc., SIL Open Font License 1.1 (stated in the font).

### Fonts

| Font | Version | Licence | Files |
|---|---|---|---|
| Google Sans Flex (Regular, Medium, SemiBold), copyright 2015 Google LLC | 5.000 | SIL Open Font License 1.1 (`font-licenses/GoogleSansFlex-OFL.txt`) | `app/src/main/res/font/google_sans_*.ttf` |
| Playfair Display (Regular, Medium), copyright 2017 The Playfair Display Project Authors, Reserved Font Name "Playfair Display" | 1.203 | SIL Open Font License 1.1 (`font-licenses/PlayfairDisplay-OFL.txt`) | `app/src/main/res/font/playfair_*.ttf` |

## Senses: vision and hearing

Everything in this section runs on the phone. Photos, camera frames and recordings are never
uploaded.

### Libraries

| Library | Version | Licence | Source |
|---|---|---|---|
| MediaPipe Tasks Vision (`com.google.mediapipe:tasks-vision`) | 0.10.35 | Apache-2.0 | https://github.com/google-ai-edge/mediapipe |
| MediaPipe Tasks Audio (`com.google.mediapipe:tasks-audio`) | 0.10.35 | Apache-2.0 | https://github.com/google-ai-edge/mediapipe |
| MediaPipe Tasks Core (`com.google.mediapipe:tasks-core`, pulled in by the two above) | 0.10.35 | Apache-2.0 | https://github.com/google-ai-edge/mediapipe |
| CameraX core, camera2, lifecycle, view (`androidx.camera:*`) | 1.6.2 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/camera |

MediaPipe Tasks also brings in Guava, Flogger, protobuf-javalite, Google's datatransport library
and its firebase-encoders, and AutoValue annotations (all Apache-2.0, except protobuf-javalite:
BSD-3-Clause). CameraX brings in camera-video, camera-camera2-pipe, viewfinder-core,
featurecombinationquery and concurrent-futures (all Apache-2.0). MediaPipe uses datatransport to report
usage statistics to Google; MySI unregisters datatransport's upload backend in its manifest, so
those reports are dropped on the phone and never sent.

### Bundled models

Both models are stored uncompressed in `app/src/main/assets/` and loaded from there; nothing is
downloaded at run time.

#### `efficientdet_lite0.tflite`: object detector

- Model: EfficientDet-Lite0, float16 weights, 320 x 320 input, 80 COCO object classes, as
  published for the MediaPipe Object Detector task (Google's recommended model for it).
- Source: https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float16/1/efficientdet_lite0.tflite
  (linked as "Latest" from https://developers.google.com/edge/mediapipe/solutions/vision/object_detector).
- Size: 7,254,339 bytes. MD5 matched the storage server's `x-goog-hash` on download.
- SHA-256: `4b59100025bea1235a84c1038879a6cccc9f6c49f5e41144e91e74d99e780993`
- Licence: Apache-2.0. The file itself carries no licence metadata and the MediaPipe page does
  not state one. Google's official listing of the same model family on Kaggle
  (https://www.kaggle.com/models/tensorflow/efficientdet, API checked 11 November 2026) lists
  every variant, including `tfLite/lite0-detection-default`, `tfLite/lite0-detection-metadata` and
  `tfLite/lite0-int8`, as "Apache 2.0"; Google's LiteRT community copy of Lite0 on Hugging Face
  (https://huggingface.co/litert-community/efficientdet) is also marked apache-2.0.
- Why this variant: measured on the emulator against the int8, float16 and float32 builds of
  Lite0 and Lite2 on MediaPipe's test pictures, it gave the most correct counts (the only one, with
  Lite0 float32, to find both cats and both dogs in `cats_and_dogs.jpg`) at about 85 ms per
  picture; Lite0 float32 gave identical results at twice the size.

#### `yamnet.tflite`: sound classifier

- Model: YAMNet, float32, 521 AudioSet sound classes, 0.975 s windows of 16 kHz mono audio, as
  published for the MediaPipe Audio Classifier task.
- Source: https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/1/yamnet.tflite
  (linked as "Latest" from https://developers.google.com/edge/mediapipe/solutions/audio/audio_classifier).
- Size: 4,126,810 bytes. MD5 matched the storage server's `x-goog-hash` on download.
- SHA-256: `4d8b4a53282dc83ef04e3e7dbc4fbc98082e34e44ed798e16c3a0cdd4c584faf`
- Licence: Apache-2.0, stated in the model's own embedded metadata ("Apache License. Version 2.0
  http://www.apache.org/licenses/LICENSE-2.0.").

## Models

The language models MySI runs are not bundled with the app. They are downloaded only when the
user asks, from the "Get a model" screen, straight from Google's LiteRT community pages on
Hugging Face (https://huggingface.co/litert-community). Each file is pinned to a commit and
checked against its published SHA-256 before it is used. The list comes from `models.json`
(built into the app; a newer copy may be fetched from this repository). A user can also load a
`.litertlm` file they already have.

| Model | Made by | Licence | Source (pinned commit) |
|---|---|---|---|
| Gemma 4 E2B (`gemma-4-E2B-it.litertlm`) | Google | Apache-2.0 | https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/2d74ac41a33d7da82f39f7d5720a04c68514a103 |
| Gemma 4 E4B (`gemma-4-E4B-it.litertlm`) | Google | Apache-2.0 | https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/tree/f254b5ba6e36f7d89cc7892e268e9877bc20f0a0 |
| Gemma 4 12B (`gemma-4-12B-it.litertlm`) | Google | Apache-2.0 | https://huggingface.co/litert-community/gemma-4-12B-it-litert-lm/tree/689ac189e26fa5ca9b59b9f9e067690c1be3810b |
| SmolLM3 3B (`SmolLM3-3B_q4_block32_ekv4096.litertlm`) | Hugging Face | Apache-2.0 | https://huggingface.co/litert-community/SmolLM3-3B/tree/3ac335e35f3fd082bdc64c2503e53365d0673528 |
| Qwen3 1.7B (`Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm`) | Alibaba Qwen | Apache-2.0 | https://huggingface.co/litert-community/Qwen3-1.7B/tree/c421cc3775575372ac2ca4aa1bebc97300ae36b6 |
| Qwen3 0.6B (`Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm`) | Alibaba Qwen | Apache-2.0 | https://huggingface.co/litert-community/Qwen3-0.6B/tree/e6bb3e4a1eb3f663153f0c1c12b735d64be657a7 |

Only the two Gemma 4 phone models (E2B and E4B) are tested with MySI's tools, photos and audio,
and only they are ever recommended. The others are listed as untested. Debug builds also list a
small Apache-2.0 file (`embeddinggemma-2-text-270m.litertlm`, from
https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm) used only to test
the download path; release builds do not include it.
