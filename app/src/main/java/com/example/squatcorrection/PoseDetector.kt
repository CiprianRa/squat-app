package com.example.squatcorrection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.concurrent.Executors

class PoseDetector(
    private val context: Context,
    private val resultListener: PoseResultListener
) {
    companion object {
        private const val TAG = "PoseDetector"
        private const val MODEL_POSE_LANDMARKER_HEAVY = "pose_landmarker_heavy.task"
    }

    interface PoseResultListener {
        fun onResults(result: ResultBundle)
        fun onError(error: String, errorCode: Int = 0)
    }

    data class ResultBundle(
        val results: List<PoseLandmarkerResult>,
        val inferenceTime: Long,
        val inputImageHeight: Int,
        val inputImageWidth: Int
    )

    private var poseLandmarker: PoseLandmarker? = null
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    fun setupPoseLandmarker(): Boolean {
        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_POSE_LANDMARKER_HEAVY)
                .setDelegate(Delegate.GPU)
                .build()

            val optionsBuilder = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(0.5f)
                .setMinPosePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener { result, mpImage ->
                    val finishTimeMs = SystemClock.uptimeMillis()
                    val inferenceTime = finishTimeMs - result.timestampMs()

                    resultListener.onResults(
                        ResultBundle(
                            results = listOf(result),
                            inferenceTime = inferenceTime,
                            inputImageHeight = mpImage.height,
                            inputImageWidth = mpImage.width
                        )
                    )
                }
                .setErrorListener { error ->
                    resultListener.onError(error.message ?: "Unknown error")
                }

            val options = optionsBuilder.build()
            poseLandmarker = PoseLandmarker.createFromOptions(context, options)

            Log.d(TAG, "MediaPipe Pose Landmarker initialized successfully")
            true
        } catch (e: IllegalStateException) {
            Log.e(TAG, "MediaPipe failed to load the task with error: ${e.message}")
            resultListener.onError("Pose Landmarker failed to initialize. See error logs for details")
            false
        } catch (e: RuntimeException) {
            Log.e(TAG, "GPU delegate failed, trying CPU fallback: ${e.message}")

            try {
                val cpuBaseOptions = BaseOptions.builder()
                    .setModelAssetPath(MODEL_POSE_LANDMARKER_HEAVY)
                    .setDelegate(Delegate.CPU)
                    .build()

                val cpuOptionsBuilder = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(cpuBaseOptions)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumPoses(1)
                    .setMinPoseDetectionConfidence(0.5f)
                    .setMinPosePresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setResultListener { result, mpImage ->
                        val finishTimeMs = SystemClock.uptimeMillis()
                        val inferenceTime = finishTimeMs - result.timestampMs()

                        resultListener.onResults(
                            ResultBundle(
                                results = listOf(result),
                                inferenceTime = inferenceTime,
                                inputImageHeight = mpImage.height,
                                inputImageWidth = mpImage.width
                            )
                        )
                    }
                    .setErrorListener { error ->
                        resultListener.onError(error.message ?: "Unknown error")
                    }

                val cpuOptions = cpuOptionsBuilder.build()
                poseLandmarker = PoseLandmarker.createFromOptions(context, cpuOptions)

                Log.d(TAG, "MediaPipe Pose Landmarker initialized with CPU fallback")
                true
            } catch (fallbackException: Exception) {
                Log.e(TAG, "CPU fallback also failed: ${fallbackException.message}")
                resultListener.onError("Initialization failed completely: ${fallbackException.message}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up pose landmarker: ${e.message}")
            resultListener.onError("Initialization failed: ${e.message}")
            false
        }
    }

    fun detectLiveStream(imageProxy: ImageProxy, isFrontCamera: Boolean = false) {
        if (poseLandmarker == null) {
            Log.e(TAG, "Pose landmarker is not initialized")
            imageProxy.close()
            return
        }

        val frameTime = SystemClock.uptimeMillis()

        try {
            val bitmapBuffer = Bitmap.createBitmap(
                imageProxy.width,
                imageProxy.height,
                Bitmap.Config.ARGB_8888
            )

            imageProxy.use {
                bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer)
            }
            imageProxy.close()

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

                if (isFrontCamera) {
                    postScale(
                        -1f,
                        1f,
                        imageProxy.width.toFloat(),
                        imageProxy.height.toFloat()
                    )
                }
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
                matrix, true
            )

            val mpImage = BitmapImageBuilder(rotatedBitmap).build()

            poseLandmarker?.detectAsync(mpImage, frameTime)


        } catch (e: Exception) {
            Log.e(TAG, "Error during pose detection: ${e.message}")
            resultListener.onError("Detection failed: ${e.message}")
            imageProxy.close()
        }
    }

    fun clearPoseLandmarker() {
        backgroundExecutor.execute {
            poseLandmarker?.close()
            poseLandmarker = null
        }
    }

    fun isClose(): Boolean {
        return poseLandmarker == null
    }

    fun close() {
        clearPoseLandmarker()
        backgroundExecutor.shutdown()
    }
}