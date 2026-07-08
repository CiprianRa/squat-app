package com.example.squatcorrection

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class SquatAnalyzer(private val context: Context,
                    private val poseOverlay: PoseOverlay?=null
                    ) {
    companion object {
        private const val TAG = "SquatAnalyzer"

        private const val LEFT_HIP = 23
        private const val RIGHT_HIP = 24
        private const val LEFT_KNEE = 25
        private const val RIGHT_KNEE = 26
        private const val LEFT_ANKLE = 27
        private const val RIGHT_ANKLE = 28
        private const val LEFT_HEEL = 29
        private const val RIGHT_HEEL = 30
        private const val LEFT_SHOULDER = 11
        private const val RIGHT_SHOULDER = 12
        private const val LEFT_FOOT = 31
        private const val RIGHT_FOOT = 32

        private const val WAIT_FRAMES = 30
        private const val HEIGHT_THRESHOLD = 0.04f
        private const val RETURN_THRESHOLD = 0.015f
        private const val WARMUP_DURATION_MS = 5000L
    }

    private var interpreter: Interpreter? = null
    private val inputBuffer: ByteBuffer = ByteBuffer.allocateDirect(1 * 60 * 28 * 4).apply {
        order(ByteOrder.nativeOrder())
    }
    private val outputBuffer: Array<FloatArray> = arrayOf(FloatArray(4))

    private val labelMap = mapOf(
        0 to "Corecte",
        1 to "Genunchi",
        2 to "Adancime",
        3 to "Calcaie"
    )

    private var phase = "WARMUP"
    private var frameCounter = 0
    private var initialHeight: Float? = null
    private var isCollecting = false
    private val startTime = System.currentTimeMillis()

    private val landmarksBuffer = mutableListOf<PoseLandmarkerResult>()
    private val frameIdBuffer = mutableListOf<Int>()
    private val idleBefore = mutableListOf<PoseLandmarkerResult>()
    private val idleAfter = mutableListOf<PoseLandmarkerResult>()
    private var collectAfter = false

    private val audioFeedback = AudioFeedback(context)
    private var lastAudioFeedback = ""
    private var lastAudioTime = 0L
    private val audioCooldownMs = 1000L

    private var correctSquatsCount = 0

    var lastPrediction: String = ""
        private set

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            val modelBuffer = FileUtil.loadMappedFile(context, "squat_manual_lstm_combined_angles2.tflite")
            interpreter = Interpreter(modelBuffer)
            Log.d(TAG, "TFLite model loaded successfully!")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading TFLite model: $e")
        }
    }

    private fun getMidHipHeight(result: PoseLandmarkerResult): Float? {
        if (result.landmarks().isEmpty()) return null

        val landmarks = result.landmarks()[0]
        if (landmarks.size <= maxOf(LEFT_HIP, RIGHT_HIP)) return null

        val leftHip = landmarks[LEFT_HIP]
        val rightHip = landmarks[RIGHT_HIP]

        val visL = leftHip.visibility().isPresent && leftHip.visibility().get() > 0.6f
        val visR = rightHip.visibility().isPresent && rightHip.visibility().get() > 0.6f

        return when {
            visL && visR -> (leftHip.y() + rightHip.y()) / 2f
            visL -> leftHip.y()
            visR -> rightHip.y()
            else -> null
        }
    }

    private fun getPoint(result: PoseLandmarkerResult, index: Int): Pair<FloatArray, Float>? {
        if (result.landmarks().isEmpty()) return null
        val landmarks = result.landmarks()[0]
        if (index >= landmarks.size) return null

        val landmark = landmarks[index]
        val coord = floatArrayOf(landmark.x(), landmark.y())
        val visibility = if (landmark.visibility().isPresent) landmark.visibility().get() else 0f
        return Pair(coord, visibility)
    }

    private fun normalizeLandmarks(result: PoseLandmarkerResult, refMidHip: FloatArray, refFemurLen: Float): FloatArray? {
        try {
            if (!refFemurLen.isFinite() || refFemurLen < 1e-5f) return null

            val points = mutableMapOf<String, FloatArray>()
            val landmarkIndices = mapOf(
                "LEFT_HIP" to LEFT_HIP, "RIGHT_HIP" to RIGHT_HIP,
                "LEFT_KNEE" to LEFT_KNEE, "RIGHT_KNEE" to RIGHT_KNEE,
                "LEFT_ANKLE" to LEFT_ANKLE, "RIGHT_ANKLE" to RIGHT_ANKLE,
                "LEFT_HEEL" to LEFT_HEEL, "RIGHT_HEEL" to RIGHT_HEEL,
                "LEFT_SHOULDER" to LEFT_SHOULDER, "RIGHT_SHOULDER" to RIGHT_SHOULDER,
                "LEFT_FOOT" to LEFT_FOOT, "RIGHT_FOOT" to RIGHT_FOOT
            )

            for ((name, index) in landmarkIndices) {
                val pointData = getPoint(result, index) ?: continue
                val coord = pointData.first
                val normalizedCoord = floatArrayOf(
                    (coord[0] - refMidHip[0]) / refFemurLen,
                    (coord[1] - refMidHip[1]) / refFemurLen
                )
                points[name] = normalizedCoord
            }

            val leftHip = points["LEFT_HIP"] ?: return null
            val leftHeel = points["LEFT_HEEL"] ?: return null
            val leftAnkle = points["LEFT_ANKLE"] ?: return null
            val leftShoulder = points["LEFT_SHOULDER"] ?: return null
            val rightShoulder = points["RIGHT_SHOULDER"] ?: return null

            val hipHeelDist = sqrt((leftHip[0] - leftHeel[0]).pow(2) + (leftHip[1] - leftHeel[1]).pow(2))
            val hipAnkleDist = sqrt((leftHip[0] - leftAnkle[0]).pow(2) + (leftHip[1] - leftAnkle[1]).pow(2))
            val shoulderHeelDist = sqrt((leftShoulder[0] - leftHeel[0]).pow(2) + (leftShoulder[1] - leftHeel[1]).pow(2))
            val shoulderGap = abs(leftShoulder[0] - rightShoulder[0])

            // Build feature vector
            val features = mutableListOf<Float>()
            for ((_, index) in landmarkIndices) {
                val point = points[landmarkIndices.entries.find { it.value == index }?.key] ?: floatArrayOf(0f, 0f)
                features.addAll(point.toList())
            }
            features.addAll(listOf(hipHeelDist, hipAnkleDist, shoulderHeelDist, shoulderGap))

            return features.toFloatArray()
        } catch (e: Exception) {
            Log.e(TAG, "Error in normalizeLandmarks: $e")
            return null
        }
    }

    private fun interpolateToFixedLength(data: List<FloatArray>, targetLen: Int = 60): Array<FloatArray> {
        if (data.isEmpty()) return emptyArray()
        if (data.size == 1) {
            return Array(targetLen) { data[0].clone() }
        }

        val featureCount = data[0].size
        val result = Array(targetLen) { FloatArray(featureCount) }

        for (featureIdx in 0 until featureCount) {
            val values = data.map { it[featureIdx] }.toFloatArray()
            val interpolated = interpolateArray(values, targetLen)
            for (frameIdx in 0 until targetLen) {
                result[frameIdx][featureIdx] = interpolated[frameIdx]
            }
        }

        return result
    }

    private fun interpolateArray(values: FloatArray, targetLen: Int): FloatArray {
        val result = FloatArray(targetLen)
        val step = (values.size - 1).toFloat() / (targetLen - 1)

        for (i in 0 until targetLen) {
            val index = i * step
            val lowerIndex = index.toInt()
            val upperIndex = minOf(lowerIndex + 1, values.size - 1)
            val fraction = index - lowerIndex

            result[i] = values[lowerIndex] * (1 - fraction) + values[upperIndex] * fraction
        }

        return result
    }

    private fun predictWithTFLite(inputData: Array<FloatArray>): String? {
        val interpreter = this.interpreter ?: run {
            Log.e(TAG, "Interpreter is null!")
            return null
        }

        try {
            Log.d(TAG, "Starting prediction with shape: ${inputData.size}x${inputData[0].size}")

            inputBuffer.rewind()
            var valueCount = 0
            for (frame in inputData) {
                for (feature in frame) {
                    if (!feature.isFinite()) {
                        Log.e(TAG, "Non-finite value detected: $feature")
                        return null
                    }
                    inputBuffer.putFloat(feature)
                    valueCount++
                }
            }

            Log.d(TAG, "Input buffer filled with $valueCount values")

            interpreter.run(inputBuffer, outputBuffer)
            Log.d(TAG, "Inference completed successfully")

            val confidences = outputBuffer[0]
            Log.d(TAG, "Raw output: [${confidences.joinToString(", ") { "%.3f".format(it) }}]")

            val maxIndex = confidences.indices.maxByOrNull { confidences[it] } ?: 0
            val maxConfidence = confidences[maxIndex]

            val prediction = labelMap[maxIndex] ?: "Unknown"
            Log.d(TAG, "Prediction: $prediction (index: $maxIndex, confidence: ${String.format("%.3f", maxConfidence)})")

            return prediction
        } catch (e: Exception) {
            Log.e(TAG, "TFLite inference error: $e")
            e.printStackTrace()
            return null
        }
    }

    private fun updatePhase(height: Float, result: PoseLandmarkerResult) {
        when (phase) {
            "WARMUP" -> {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= WARMUP_DURATION_MS) {
                    phase = "WAITING"
                    Log.d(TAG, "[$frameCounter] Phase: WARMUP → WAITING (${elapsed}ms elapsed)")
                }
                return
            }

            "WAITING" -> {
                if (frameCounter >= WAIT_FRAMES + (WARMUP_DURATION_MS / 33).toInt()) {
                    phase = "IDLE"
                    initialHeight = height
                    Log.d(TAG, "[$frameCounter] Phase: WAITING → IDLE")
                }
            }

            "IDLE" -> {
                idleBefore.add(result)
                if (idleBefore.size > 20) {
                    idleBefore.removeAt(0)
                }

                initialHeight?.let { initHeight ->
                    if (height > initHeight + HEIGHT_THRESHOLD) {
                        phase = "PHASE_2"
                        isCollecting = true
                        landmarksBuffer.clear()
                        frameIdBuffer.clear()
                        Log.d(TAG, "[$frameCounter] Phase: IDLE → PHASE_2 (start squat)")
                    }
                }
            }

            "PHASE_2" -> {
                initialHeight?.let { initHeight ->
                    if (abs(height - initHeight) < RETURN_THRESHOLD) {
                        phase = "PHASE_3"
                        isCollecting = false
                        Log.d(TAG, "[$frameCounter] Phase: PHASE_2 → PHASE_3 (end squat)")
                        collectAfter = true
                        idleAfter.clear()
                    }
                }
            }

            "PHASE_3" -> {
                phase = "IDLE"
                initialHeight = height
                Log.d(TAG, "[$frameCounter] Phase: PHASE_3 → IDLE (reset)")
            }
        }
    }

    fun processLandmarks(result: PoseLandmarkerResult) {
        frameCounter++

        if (phase == "WARMUP") {
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed < WARMUP_DURATION_MS) {
                return
            } else {
                phase = "WAITING"
                Log.d(TAG, "Warmup completed, starting pose analysis")
            }
        }

        if (result.landmarks().isNotEmpty()) {
            val height = getMidHipHeight(result)

            if (height != null) {
                updatePhase(height, result)

                if (isCollecting) {
                    landmarksBuffer.add(result)
                    frameIdBuffer.add(frameCounter)
                }

                if (collectAfter) {
                    idleAfter.add(result)
                    if (idleAfter.size == 20) {
                        collectAfter = false
                        processCompleteSequence()
                    }
                }
            }
        } else {
            if (phase != "WARMUP") {
                phase = "WAITING"
                initialHeight = null
                isCollecting = false
                idleBefore.clear()
                idleAfter.clear()
                landmarksBuffer.clear()
            }
        }
    }

    private fun processCompleteSequence() {
        val totalFrames = idleBefore.size + landmarksBuffer.size + idleAfter.size
        Log.d(TAG, """
                Secventa completa capturata
             - Idle inainte: ${idleBefore.size} frame-uri
             - Executie:     ${landmarksBuffer.size} frame-uri  
             - Idle dupa:    ${idleAfter.size} frame-uri
             - TOTAL:        $totalFrames frame-uri
        """.trimIndent())

        if (idleBefore.isEmpty()) return

        val leftHipData = getPoint(idleBefore[0], LEFT_HIP)
        val rightHipData = getPoint(idleBefore[0], RIGHT_HIP)

        val refMidHip = when {
            leftHipData != null && rightHipData != null && leftHipData.second >= 0.5f && rightHipData.second >= 0.5f -> {
                floatArrayOf(
                    (leftHipData.first[0] + rightHipData.first[0]) / 2f,
                    (leftHipData.first[1] + rightHipData.first[1]) / 2f
                )
            }
            leftHipData != null && leftHipData.second >= 0.5f -> leftHipData.first
            rightHipData != null && rightHipData.second >= 0.5f -> rightHipData.first
            else -> null
        }

        val refFemurLen = if (refMidHip != null && leftHipData != null && leftHipData.second >= 0.5f) {
            val leftKneeData = getPoint(idleBefore[0], LEFT_KNEE)
            if (leftKneeData != null && leftKneeData.second >= 0.5f) {
                sqrt((leftHipData.first[0] - leftKneeData.first[0]).pow(2) +
                        (leftHipData.first[1] - leftKneeData.first[1]).pow(2))
            } else null
        } else null

        if (refMidHip == null || refFemurLen == null || refFemurLen < 1e-5f) {
            Log.e(TAG, "Cannot normalize - missing reference points")
            return
        }

        val allResults = idleBefore + landmarksBuffer + idleAfter
        val validNorms = mutableListOf<FloatArray>()

        for (result in allResults) {
            if (result.landmarks().isNotEmpty()) {
                val normalized = normalizeLandmarks(result, refMidHip, refFemurLen)
                if (normalized != null && normalized.all { it.isFinite() } && normalized.all { abs(it) <= 5f }) {
                    validNorms.add(normalized)
                }
            }
        }

        if (validNorms.isNotEmpty()) {
            val interpData = interpolateToFixedLength(validNorms, 60)
            Log.d(TAG, "[DEBUG] Input shape: ${interpData.size}x${if (interpData.isNotEmpty()) interpData[0].size else 0}")
            Log.d(TAG, "[DEBUG] Valid norms count: ${validNorms.size}")
            Log.d(TAG, "[DEBUG] First feature sample: [${interpData[0].take(5).joinToString(", ") { "%.3f".format(it) }}...]")

            val prediction = predictWithTFLite(interpData)
            if (prediction != null) {
                lastPrediction = prediction
                if (prediction == "Corecte") {
                    correctSquatsCount++
                }
                Log.d(TAG, "FINAL PREDICTION SET: $prediction")
                val currentTime = System.currentTimeMillis()
                if (prediction != lastAudioFeedback &&
                    currentTime - lastAudioTime > audioCooldownMs) {

                    playAudioForPrediction(prediction)
                    lastAudioFeedback = prediction
                    lastAudioTime = currentTime
                }
                if (prediction in listOf("Genunchi", "Adancime", "Calcaie")) {
                    poseOverlay?.flashError()
                    Log.d(TAG, "Flash: $prediction")
                }
            } else {
                Log.e(TAG, "Prediction returned null")
            }
        } else {
            Log.e(TAG, "No valid normalized data for prediction (validNorms.size = ${validNorms.size})")
            // Debug normalization issues
            Log.d(TAG, "[DEBUG] Total results: ${allResults.size}")
            for (i in allResults.indices.take(3)) {
                val result = allResults[i]
                if (result.landmarks().isNotEmpty()) {
                    val norm = normalizeLandmarks(result, refMidHip, refFemurLen)
                    Log.d(TAG, "[DEBUG] Result $i: norm = ${norm?.size ?: "null"}")
                }
            }
        }
    }

    private fun playAudioForPrediction(prediction: String) {
        when (prediction) {
            "Genunchi" -> {
                audioFeedback.playKneeError()
                Log.d(TAG, "Audio: Knee Error")
            }
            "Adancime" -> {
                audioFeedback.playDepthError()
                Log.d(TAG, "Audio: Depth error")
            }
            "Calcaie" -> {
                audioFeedback.playHeelError()
                Log.d(TAG, "Audio: Heel error")
            }
            "Corecte" -> {
                audioFeedback.playCorrectSquat()
                Log.d(TAG, "Audio: Correct")
            }
        }
    }

    fun getCorrectSquatsCount(): Int {
        return correctSquatsCount
    }

    fun getRemainingWarmupTime(): Int {
        if (phase != "WARMUP") return 0
        val elapsed = System.currentTimeMillis() - startTime
        val remaining = ((WARMUP_DURATION_MS - elapsed) / 1000).toInt()
        return maxOf(0, remaining + 1)
    }

    fun getCurrentPhase(): String = phase
    fun getFrameCounter(): Int = frameCounter

    fun close() {
        interpreter?.close()
        audioFeedback.release()
    }
}