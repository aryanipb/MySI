package com.mysi.agent.senses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageMathTest {
    @Test
    fun sampleSizeNeverDropsBelowTheTarget() {
        assertEquals(2, ImageMath.sampleSize(4000, 3000, 1024))
        assertEquals(4, ImageMath.sampleSize(8000, 6000, 1024))
        assertEquals(1, ImageMath.sampleSize(1500, 1000, 1024))
        assertEquals(1, ImageMath.sampleSize(800, 600, 1024))
        assertEquals(2, ImageMath.sampleSize(3000, 4000, 1024))
    }

    @Test
    fun fitKeepsAspectAndNeverEnlarges() {
        assertEquals(Size(1024, 768), ImageMath.fit(2000, 1500, 1024))
        assertEquals(Size(576, 1024), ImageMath.fit(1080, 1920, 1024))
        assertEquals(Size(640, 480), ImageMath.fit(640, 480, 1024))
        assertEquals(Size(1024, 1), ImageMath.fit(5000, 2, 1024))
    }

    @Test
    fun exifOrientationsMatchTheStandard() {
        val expected = mapOf(
            1 to Orientation(0, false), 2 to Orientation(0, true), 3 to Orientation(180, false), 4 to Orientation(180, true),
            5 to Orientation(270, true), 6 to Orientation(90, false), 7 to Orientation(90, true), 8 to Orientation(270, false),
            0 to Orientation.UPRIGHT, 9 to Orientation.UPRIGHT,
        )
        expected.forEach { (tag, o) -> assertEquals("tag $tag", o, Orientation.fromExif(tag)) }
        // A phone held upright usually stores landscape pixels with tag 6: the picture becomes portrait.
        assertEquals(Size(3000, 4000), ImageMath.oriented(Size(4000, 3000), Orientation.fromExif(6)))
        assertEquals(Size(4000, 3000), ImageMath.oriented(Size(4000, 3000), Orientation.fromExif(3)))
        assertTrue((5..8).all { Orientation.fromExif(it).swapsSides })
        assertFalse((1..4).any { Orientation.fromExif(it).swapsSides })
    }

    @Test
    fun fillCenterCropsTheLongerSide() {
        // A 4:3 frame (480 x 640 upright) on a 1080 x 1920 view: scaled by height, cropped left and right.
        val t = ImageMath.fillCenter(Size(480, 640), Size(1080, 1920))
        assertEquals(3f, t.scale, 1e-6f)
        assertEquals(-180f, t.dx, 1e-4f)
        assertEquals(0f, t.dy, 1e-4f)
        assertEquals(540f, t.x(240f), 1e-4f)
        assertEquals(960f, t.y(320f), 1e-4f)
    }
}
