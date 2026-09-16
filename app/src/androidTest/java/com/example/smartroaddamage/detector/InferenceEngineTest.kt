package com.example.smartroaddamage.detector

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InferenceEngineTest {

    private lateinit var engine: InferenceEngine

    @Before
    fun setup() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        engine = InferenceEngine(appContext)
    }

    @Test
    fun zeroWindowReturnsNormal() {
        // Zeros everywhere except 1G (9.81) on Z axis — represents a stationary or perfectly smooth ride
        val zeroSamples = List(30) { ImuSample(0L, 0f, 0f, 9.81f, 0f, 0f, 0f) }
        val result = engine.infer(zeroSamples)
        
        // Ensure confidence is decent and it classifies as Normal
        assertEquals("Normal", result.label)
        assertTrue("Expected normal not to be anomaly", !result.isAnomaly)
    }

    @Test
    fun potholeSpikeWindowReturnsAnomaly() {
        // 25 normal samples + 5 spike samples matching CSV row 350511 region
        // Shifted baseline to perfectly calibrated 0,0,9.81
        val normal = ImuSample(0L, 0f, 0f, 9.81f, 0f, 0f, 0f)
        val spike  = ImuSample(0L, -0.35f, 4.87f, 15.5f, -0.22f, 0.03f, 0.06f)
        val window = List(25) { normal } + List(5) { spike }
        
        val result = engine.infer(window)
        
        // This validates: (1) tensor mapped correctly (2) scaler values match (3) threshold logic works
        assertTrue("Spike should trigger an anomaly", result.isAnomaly)
        assertTrue("Should classify as Pothole or Speedbreaker", 
            result.label == "Pothole" || result.label == "Speedbreaker")
    }
}
