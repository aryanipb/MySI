package com.mysi.agent.senses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import java.io.Closeable
import com.mysi.agent.senses.Detection as Found

/**
 * The bundled object detector: EfficientDet-Lite0 (float16 weights, 320 x 320 input, 80 COCO
 * object types) on MediaPipe Tasks, CPU. Chosen by measurement on the emulator: on MediaPipe's
 * own test pictures it was the only published variant that counted both the cats and the dogs in
 * cats_and_dogs.jpg (Lite2 int8/float16/float32 missed both dogs; Lite0 int8 called them cats),
 * and it ran in about 85 ms against Lite2's 300 ms. One instance is not thread-safe; [image] mode
 * is for single pictures, [video] mode for camera frames in order.
 */
class ObjectFinder private constructor(private val detector: ObjectDetector) : Closeable {
    /** Detections in [bitmap]'s own pixel coordinates. */
    fun detect(bitmap: Bitmap): List<Found> = convert(detector.detect(BitmapImageBuilder(bitmap).build()))

    /** For camera frames: [timestampMs] must increase from frame to frame. */
    fun detectFrame(bitmap: Bitmap, timestampMs: Long): List<Found> = convert(detector.detectForVideo(BitmapImageBuilder(bitmap).build(), timestampMs))

    override fun close() = detector.close()

    companion object {
        const val MODEL = "efficientdet_lite0.tflite"

        /** Raw detections kept from the model; the report applies its own, stricter threshold. */
        private const val RAW_THRESHOLD = 0.3f
        private const val MAX_RESULTS = 100

        fun image(ctx: Context) = create(ctx, RunningMode.IMAGE)

        fun video(ctx: Context) = create(ctx, RunningMode.VIDEO)

        private fun create(ctx: Context, mode: RunningMode) =
            ObjectFinder(
                ObjectDetector.createFromOptions(
                    ctx.applicationContext,
                    ObjectDetector.ObjectDetectorOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
                        .setRunningMode(mode)
                        .setMaxResults(MAX_RESULTS)
                        .setScoreThreshold(RAW_THRESHOLD)
                        .build(),
                )
            )

        private fun convert(r: ObjectDetectorResult): List<Found> =
            r.detections().mapNotNull { d ->
                val c = d.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
                val b = d.boundingBox()
                Found(c.categoryName().ifBlank { c.displayName() }, c.score(), Box(b.left, b.top, b.right, b.bottom))
            }

        /** A copy of [bitmap] with each detection's box and label drawn on it. */
        fun annotate(bitmap: Bitmap, dets: List<Found>): Bitmap {
            val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(out)
            val stroke = maxOf(2f, maxOf(out.width, out.height) / 300f)
            val box = Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                color = Color.rgb(255, 214, 10)
                isAntiAlias = true
            }
            val textSize = maxOf(14f, maxOf(out.width, out.height) / 45f)
            val label = Paint().apply {
                color = Color.BLACK
                this.textSize = textSize
                isAntiAlias = true
                isFakeBoldText = true
            }
            val tag = Paint().apply { color = Color.rgb(255, 214, 10) }
            for (d in dets) {
                val r = RectF(d.box.left, d.box.top, d.box.right, d.box.bottom)
                canvas.drawRect(r, box)
                val text = "${d.label} ${(d.score * 100).toInt()}%"
                val w = label.measureText(text) + stroke * 2
                val top = if (r.top - textSize - stroke * 2 >= 0) r.top - textSize - stroke * 2 else r.top
                canvas.drawRect(r.left, top, r.left + w, top + textSize + stroke * 2, tag)
                canvas.drawText(text, r.left + stroke, top + textSize, label)
            }
            return out
        }
    }
}

/** The bundled YAMNet sound classifier (MediaPipe Tasks, CPU), for whole clips. */
class SoundClassifier private constructor(private val classifier: AudioClassifier) : Closeable {
    /** Labels for each 0.975 s window of [pcm], which must be 16 kHz mono. */
    fun classify(pcm: Pcm): List<SoundWindow> {
        require(pcm.sampleRate == Media.SAMPLE_RATE) { "Sound classification needs 16 kHz audio." }
        val data = AudioData.create(
            AudioData.AudioDataFormat.builder().setNumOfChannels(1).setSampleRate(pcm.sampleRate.toFloat()).build(),
            pcm.samples.size,
        )
        data.load(FloatArray(pcm.samples.size) { pcm.samples[it] / 32768f })
        val r = classifier.classify(data)
        return r.classificationResults().mapIndexed { i, cr ->
            val labels = cr.classifications().flatMap { it.categories() }.map { it.categoryName().ifBlank { it.displayName() } to it.score() }
            SoundWindow(cr.timestampMs().orElse(i * SoundReport.WINDOW_MS), labels)
        }
    }

    override fun close() = classifier.close()

    companion object {
        const val MODEL = "yamnet.tflite"

        fun create(ctx: Context) =
            SoundClassifier(
                AudioClassifier.createFromOptions(
                    ctx.applicationContext,
                    AudioClassifier.AudioClassifierOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
                        .setRunningMode(com.google.mediapipe.tasks.audio.core.RunningMode.AUDIO_CLIPS)
                        .setMaxResults(5)
                        .setScoreThreshold(0.1f)
                        .build(),
                )
            )
    }
}
