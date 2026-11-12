# Third-party notices

MySI is built on open-source libraries and models. This file lists what ships inside the app,
where it comes from and under which licence. Sections are added as features land.

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
