package com.example.smartroaddamage.detector

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Headless camera + ONNX inference lane for the road-scene segmenter.
 *
 * Model contract recovered from the training notebook:
 * - Input  : [1, 3, 128, 128] float32, ImageNet-normalized, CHW layout
 * - Output : [1, 2, 128, 128] logits where channel 0=background and 1=pothole
 *
 * Important:
 * - The exported INT8 model still expects normalized float input.
 * - The notebook does not define a deployment threshold, so the pixel-ratio gate here
 *   is a conservative heuristic tuned for low false positives.
 */
class VisionInferenceEngine(
    context: Context
) {

    enum class RuntimeStatus {
        IDLE,
        STARTING,
        RUNNING,
        MODEL_INIT_FAILED,
        CAMERA_BIND_FAILED
    }

    sealed class VisionDetection {
        object Clear : VisionDetection()

        data class PotholeDetected(
            val pixelRatio: Float,
            val horizontalCenter: Float?,
            val frameRotationDegrees: Int,
            val timestampMs: Long
        ) : VisionDetection()
    }

    private val appContext = context.applicationContext
    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val inferenceExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "vision-inference").apply { isDaemon = true }
    }
    private val sessionLoadLock = Any()

    @Volatile
    private var ortSession: OrtSession? = null

    @Volatile
    private var isCameraRunning = false

    @Volatile
    private var sessionInitializationFailed = false

    private var lastFrameTimestampMs = 0L
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var previewUseCase: Preview? = null
    private var boundPreviewView: PreviewView? = null
    private val modelFiles by lazy { extractModelAssetsToCache() }

    var onDetection: ((VisionDetection) -> Unit)? = null
    var onRunningStateChanged: ((Boolean) -> Unit)? = null
    var onStatusChanged: ((RuntimeStatus) -> Unit)? = null

    init {
        inferenceExecutor.execute(::loadSessionIfNeeded)
    }

    fun start(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView? = null
    ) {
        if (sessionInitializationFailed) {
            onStatusChanged?.invoke(RuntimeStatus.MODEL_INIT_FAILED)
            return
        }
        if (isCameraRunning && analysisUseCase != null && boundPreviewView === previewView) return

        boundPreviewView = previewView
        onStatusChanged?.invoke(RuntimeStatus.STARTING)

        val providerFuture = ProcessCameraProvider.getInstance(appContext)
        providerFuture.addListener(
            {
                try {
                    val provider = providerFuture.get()
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                        .also { useCase ->
                            useCase.setAnalyzer(inferenceExecutor, ::analyzeFrame)
                        }
                    val preview = previewView?.let { targetView ->
                        Preview.Builder()
                            .build()
                            .also { useCase ->
                                useCase.setSurfaceProvider(targetView.surfaceProvider)
                            }
                    }

                    provider.unbindAll()
                    val useCases = buildList<UseCase> {
                        add(analysis)
                        preview?.let(::add)
                    }.toTypedArray()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases)

                    cameraProvider = provider
                    analysisUseCase = analysis
                    previewUseCase = preview
                    isCameraRunning = true
                    onRunningStateChanged?.invoke(true)
                    onStatusChanged?.invoke(RuntimeStatus.RUNNING)
                    Log.i(TAG, "Headless vision camera started")
                } catch (error: Exception) {
                    isCameraRunning = false
                    previewUseCase = null
                    onRunningStateChanged?.invoke(false)
                    onStatusChanged?.invoke(RuntimeStatus.CAMERA_BIND_FAILED)
                    Log.e(TAG, "Unable to bind CameraX analysis use case", error)
                }
            },
            ContextCompat.getMainExecutor(appContext)
        )
    }

    fun stop() {
        if (!isCameraRunning && analysisUseCase == null && previewUseCase == null) return

        val provider = cameraProvider
        val analysis = analysisUseCase
        val stoppedStatus = if (sessionInitializationFailed) {
            RuntimeStatus.MODEL_INIT_FAILED
        } else {
            RuntimeStatus.IDLE
        }
        ContextCompat.getMainExecutor(appContext).execute {
            try {
                if (provider != null && analysis != null) {
                    provider.unbind(analysis)
                } else {
                    provider?.unbindAll()
                }
            } catch (error: Exception) {
                Log.w(TAG, "Failed to stop CameraX cleanly", error)
            } finally {
                analysisUseCase = null
                previewUseCase = null
                boundPreviewView = null
                isCameraRunning = false
                onRunningStateChanged?.invoke(false)
                onStatusChanged?.invoke(stoppedStatus)
            }
        }
    }

    fun destroy() {
        stop()
        try {
            ortSession?.close()
        } catch (_: Exception) {
        }
        ortSession = null
        inferenceExecutor.shutdown()
    }

    private fun loadSessionIfNeeded() {
        if (ortSession != null || sessionInitializationFailed) return

        synchronized(sessionLoadLock) {
            if (ortSession != null || sessionInitializationFailed) return

            try {
                val sessionOptions = OrtSession.SessionOptions().apply {
                    try {
                        addNnapi()
                    } catch (error: Throwable) {
                        Log.w(TAG, "NNAPI unavailable, falling back to CPU", error)
                    }
                }
                ortSession = ortEnv.createSession(modelFiles.model.absolutePath, sessionOptions)
                Log.i(TAG, "Vision model loaded: input=${ortSession?.inputNames} output=${ortSession?.outputNames}")
            } catch (error: Exception) {
                sessionInitializationFailed = true
                onStatusChanged?.invoke(RuntimeStatus.MODEL_INIT_FAILED)
                Log.e(TAG, "Failed to create ONNX session", error)

                // Stop the analysis pipeline after a hard model-load failure so the app
                // does not keep retrying the same unsupported model on every frame.
                if (isCameraRunning || analysisUseCase != null) {
                    stop()
                }
            }
        }
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        if (sessionInitializationFailed) {
            imageProxy.close()
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastFrameTimestampMs < MIN_FRAME_INTERVAL_MS) {
            imageProxy.close()
            return
        }
        lastFrameTimestampMs = now

        if (ortSession == null) {
            loadSessionIfNeeded()
        }
        val session = ortSession
        if (session == null) {
            imageProxy.close()
            return
        }

        try {
            val frameRotationDegrees = imageProxy.imageInfo.rotationDegrees
            val bitmap = imageProxy.toRgbBitmap()
            imageProxy.close()

            val scaledBitmap = Bitmap.createScaledBitmap(
                bitmap,
                MODEL_SIZE,
                MODEL_SIZE,
                true
            )
            if (scaledBitmap !== bitmap) {
                bitmap.recycle()
            }

            val inputTensor = createInputTensor(scaledBitmap)
            scaledBitmap.recycle()

            OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(inputTensor), MODEL_INPUT_SHAPE).use { tensor ->
                session.run(mapOf(INPUT_NAME to tensor)).use { result ->
                    val outputTensor = result[0] as? OnnxTensor
                    val metrics = extractPotholeSpatialMetrics(outputTensor)
                    Log.d(
                        TAG,
                        "Frame Analysis: ratio=${String.format("%.4f", metrics.pixelRatio)} " +
                            "center=${metrics.horizontalCenter?.let { String.format("%.3f", it) } ?: "n/a"} " +
                            "(threshold: $POTHOLE_PIXEL_RATIO_THRESHOLD)"
                    )
                    val detection = if (metrics.pixelRatio >= POTHOLE_PIXEL_RATIO_THRESHOLD) {
                        VisionDetection.PotholeDetected(
                            pixelRatio = metrics.pixelRatio,
                            horizontalCenter = metrics.horizontalCenter,
                            frameRotationDegrees = frameRotationDegrees,
                            timestampMs = now
                        )
                    } else {
                        VisionDetection.Clear
                    }
                    onDetection?.invoke(detection)
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Vision frame analysis failed", error)
            try {
                imageProxy.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun createInputTensor(bitmap: Bitmap): FloatArray {
        val pixelCount = MODEL_SIZE * MODEL_SIZE
        val pixels = IntArray(pixelCount)
        bitmap.getPixels(pixels, 0, MODEL_SIZE, 0, 0, MODEL_SIZE, MODEL_SIZE)

        val input = FloatArray(CHANNELS * pixelCount)
        val greenOffset = pixelCount
        val blueOffset = pixelCount * 2

        for (index in pixels.indices) {
            val pixel = pixels[index]
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f

            input[index] = (r - IMAGE_NET_MEAN[0]) / IMAGE_NET_STD[0]
            input[greenOffset + index] = (g - IMAGE_NET_MEAN[1]) / IMAGE_NET_STD[1]
            input[blueOffset + index] = (b - IMAGE_NET_MEAN[2]) / IMAGE_NET_STD[2]
        }

        return input
    }

    private fun extractPotholeSpatialMetrics(outputTensor: OnnxTensor?): PotholeSpatialMetrics {
        if (outputTensor == null) return PotholeSpatialMetrics(pixelRatio = 0f, horizontalCenter = null)

        val shape = outputTensor.info.shape
        if (shape.size != 4 || shape[1] != 2L) {
            Log.w(TAG, "Unexpected output shape: ${shape.contentToString()}")
            return PotholeSpatialMetrics(pixelRatio = 0f, horizontalCenter = null)
        }

        val height = shape[2].toInt()
        val width = shape[3].toInt()
        val planeSize = height * width
        val logits = FloatArray(planeSize * 2)
        outputTensor.floatBuffer.get(logits)
        return computePotholeSpatialMetrics(logits, height, width)
    }

    private fun ImageProxy.toRgbBitmap(): Bitmap {
        val buffer = planes.first().buffer
        val rowStride = planes.first().rowStride
        val pixelStride = planes.first().pixelStride
        val width = width
        val height = height

        val argbPixels = IntArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * rowStride
            for (x in 0 until width) {
                val offset = rowOffset + x * pixelStride
                val r = buffer.get(offset).toInt() and 0xFF
                val g = buffer.get(offset + 1).toInt() and 0xFF
                val b = buffer.get(offset + 2).toInt() and 0xFF
                val a = if (pixelStride > 3) buffer.get(offset + 3).toInt() and 0xFF else 0xFF
                argbPixels[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val bitmap = Bitmap.createBitmap(argbPixels, width, height, Bitmap.Config.ARGB_8888)
        val rotationDegrees = imageInfo.rotationDegrees
        if (rotationDegrees == 0) return bitmap

        val rotated = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            Matrix().apply { postRotate(rotationDegrees.toFloat()) },
            true
        )
        if (rotated !== bitmap) {
            bitmap.recycle()
        }
        return rotated
    }

    private fun extractModelAssetsToCache(): ModelFiles {
        val modelDestination = copyAssetToCache(MODEL_ASSET_NAME)
        val sidecarDestination = copyAssetToCache(MODEL_DATA_ASSET_NAME)
        return ModelFiles(
            model = modelDestination,
            sidecar = sidecarDestination
        )
    }

    private fun copyAssetToCache(assetName: String): File {
        val destination = File(appContext.cacheDir, assetName)
        if (destination.exists() && destination.length() > 0L) return destination

        destination.parentFile?.mkdirs()
        appContext.assets.open(assetName).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
            }
        }
        return destination
    }

    companion object {
        private const val TAG = "VisionInferenceEngine"
        private const val MODEL_ASSET_NAME = "pothole_model.onnx"
        private const val MODEL_DATA_ASSET_NAME = "pothole_model.onnx.data"
        private const val INPUT_NAME = "input"
        private const val CHANNELS = 3
        private const val MODEL_SIZE = 128
        private const val MIN_FRAME_INTERVAL_MS = 500L
        private const val POTHOLE_PIXEL_RATIO_THRESHOLD = 0.05f

        private val MODEL_INPUT_SHAPE = longArrayOf(1L, CHANNELS.toLong(), MODEL_SIZE.toLong(), MODEL_SIZE.toLong())
        private val IMAGE_NET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val IMAGE_NET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}

data class PotholeSpatialMetrics(
    val pixelRatio: Float,
    val horizontalCenter: Float?
)

internal fun computePotholeSpatialMetrics(
    logits: FloatArray,
    height: Int,
    width: Int
): PotholeSpatialMetrics {
    val planeSize = height * width
    if (width <= 0 || height <= 0 || logits.size < planeSize * 2) {
        return PotholeSpatialMetrics(pixelRatio = 0f, horizontalCenter = null)
    }

    var potholePixels = 0
    var sumX = 0L
    for (index in 0 until planeSize) {
        val backgroundScore = logits[index]
        val potholeScore = logits[index + planeSize]
        if (potholeScore > backgroundScore) {
            potholePixels++
            sumX += (index % width).toLong()
        }
    }

    val pixelRatio = potholePixels.toFloat() / planeSize.toFloat()
    val horizontalCenter = if (potholePixels == 0) {
        null
    } else {
        val normalized = (sumX.toFloat() / potholePixels.toFloat()) / maxOf(1, width - 1).toFloat()
        normalized.coerceIn(0f, 1f)
    }

    return PotholeSpatialMetrics(
        pixelRatio = pixelRatio,
        horizontalCenter = horizontalCenter
    )
}

private data class ModelFiles(
    val model: File,
    val sidecar: File
)
