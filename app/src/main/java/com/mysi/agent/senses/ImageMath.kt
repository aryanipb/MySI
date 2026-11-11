package com.mysi.agent.senses

import kotlin.math.max
import kotlin.math.roundToInt

data class Size(val width: Int, val height: Int)

/**
 * How to turn stored pixels upright: rotate clockwise by [degrees], after mirroring left-right
 * when [mirror] is set. Matches the eight EXIF orientations.
 */
data class Orientation(val degrees: Int, val mirror: Boolean) {
    val swapsSides get() = degrees == 90 || degrees == 270

    companion object {
        val UPRIGHT = Orientation(0, false)

        /** EXIF Orientation tag (1-8); anything else is treated as upright. */
        fun fromExif(tag: Int): Orientation =
            when (tag) {
                2 -> Orientation(0, true)
                3 -> Orientation(180, false)
                4 -> Orientation(180, true)
                // Transpose: mirror, then a quarter turn anticlockwise (as AndroidX ExifInterface and Glide do).
                5 -> Orientation(270, true)
                6 -> Orientation(90, false)
                // Transverse: mirror, then a quarter turn clockwise.
                7 -> Orientation(90, true)
                8 -> Orientation(270, false)
                else -> UPRIGHT
            }
    }
}

/** Pure arithmetic for preparing pictures; the Android side (Media) applies it to bitmaps. */
object ImageMath {
    /** The longest side of an image sent to the on-device model. */
    const val MODEL_MAX_SIDE = 1024

    /**
     * The largest power-of-two decode subsampling that still leaves the longest side at least
     * [maxSide], so decoding a 12-megapixel photo never holds all of its pixels, and the final
     * scaling step only ever shrinks.
     */
    fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        require(maxSide > 0)
        var s = 1
        val longest = max(width, height)
        while (longest / (s * 2) >= maxSide) s *= 2
        return s
    }

    /** [width] x [height] scaled so the longest side is at most [maxSide], keeping the aspect ratio; never enlarges. */
    fun fit(width: Int, height: Int, maxSide: Int): Size {
        require(width > 0 && height > 0 && maxSide > 0)
        val longest = max(width, height)
        if (longest <= maxSide) return Size(width, height)
        val k = maxSide.toDouble() / longest
        return Size(max(1, (width * k).roundToInt()), max(1, (height * k).roundToInt()))
    }

    /** The size after [o] is applied. */
    fun oriented(size: Size, o: Orientation) = if (o.swapsSides) Size(size.height, size.width) else size

    /**
     * Maps a frame of [src] onto a view of [view] scaled to fill it and centred (what CameraX's
     * PreviewView does by default): view = frame * scale + offset.
     */
    fun fillCenter(src: Size, view: Size): Transform {
        val scale = max(view.width.toFloat() / src.width, view.height.toFloat() / src.height)
        return Transform(scale, (view.width - src.width * scale) / 2f, (view.height - src.height * scale) / 2f)
    }

    data class Transform(val scale: Float, val dx: Float, val dy: Float) {
        fun x(v: Float) = v * scale + dx

        fun y(v: Float) = v * scale + dy
    }
}
