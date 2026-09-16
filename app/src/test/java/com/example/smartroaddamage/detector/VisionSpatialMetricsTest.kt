package com.example.smartroaddamage.detector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VisionSpatialMetricsTest {

    @Test
    fun `left heavy logits map to left horizontal center`() {
        val metrics = computePotholeSpatialMetrics(
            logits = twoPlaneLogits(
                width = 4,
                height = 2,
                potholeIndexes = setOf(0, 1, 4)
            ),
            height = 2,
            width = 4
        )

        assertEquals(3f / 8f, metrics.pixelRatio, 0.0001f)
        assertEquals(0.1111f, metrics.horizontalCenter!!, 0.001f)
    }

    @Test
    fun `center heavy logits map to centered horizontal center`() {
        val metrics = computePotholeSpatialMetrics(
            logits = twoPlaneLogits(
                width = 5,
                height = 1,
                potholeIndexes = setOf(1, 2, 3)
            ),
            height = 1,
            width = 5
        )

        assertEquals(0.6f, metrics.pixelRatio, 0.0001f)
        assertEquals(0.5f, metrics.horizontalCenter!!, 0.0001f)
    }

    @Test
    fun `empty pothole logits return null center`() {
        val metrics = computePotholeSpatialMetrics(
            logits = FloatArray(8),
            height = 1,
            width = 4
        )

        assertEquals(0f, metrics.pixelRatio, 0.0001f)
        assertNull(metrics.horizontalCenter)
    }

    @Test
    fun `invalid dimensions return safe defaults`() {
        val metrics = computePotholeSpatialMetrics(
            logits = floatArrayOf(1f, 2f),
            height = 0,
            width = 0
        )

        assertEquals(0f, metrics.pixelRatio, 0.0001f)
        assertNull(metrics.horizontalCenter)
    }

    private fun twoPlaneLogits(
        width: Int,
        height: Int,
        potholeIndexes: Set<Int>
    ): FloatArray {
        val planeSize = width * height
        val logits = FloatArray(planeSize * 2)
        for (index in 0 until planeSize) {
            logits[index] = if (index in potholeIndexes) 0f else 2f
            logits[index + planeSize] = if (index in potholeIndexes) 3f else -1f
        }
        return logits
    }
}
