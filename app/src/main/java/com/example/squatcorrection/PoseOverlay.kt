package com.example.squatcorrection

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import kotlin.math.max
import kotlin.math.min

class PoseOverlay(
    context: Context
) : View(context) {

    private var results: PoseLandmarkerResult? = null
    private var pointPaint = Paint()
    private var linePaint = Paint()

    private var scaleFactor: Float = 1f
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1
    private var offsetX: Float = 0f
    private var offsetY: Float = 0f

    private var lastUpdateTime: Long = 0
    private var frameCount: Int = 0

    private var showErrorFlash = false
    private var errorFlashStartTime = 0L
    private val errorFlashPaint = Paint().apply {
        color = Color.argb(80, 255, 0, 0)
        style = Paint.Style.FILL
    }

    init {
        initPaints()
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun clear() {
        results = null
        pointPaint.reset()
        linePaint.reset()
        invalidate()
        initPaints()
    }

    private fun initPaints() {
        linePaint.apply {
            color = Color.argb(120,0,255,0)
            strokeWidth = LANDMARK_STROKE_WIDTH
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        pointPaint.apply {
            color = Color.YELLOW
            strokeWidth = LANDMARK_STROKE_WIDTH
            style = Paint.Style.FILL
            isAntiAlias = true
        }
    }

    private fun calculateScaleAndOffset() {
        if (width == 0 || height == 0 || imageWidth == 0 || imageHeight == 0) {
            Log.d("PoseOverlay", "Cannot calculate - width:$width, height:$height, imgW:$imageWidth, imgH:$imageHeight")
            return
        }

        scaleFactor = max(width * 1f / imageWidth, height * 1f / imageHeight)
        offsetX = 0f
        offsetY = 0f

        Log.d("PoseOverlay", "Scale calculated for pose overlay - factor:$scaleFactor")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateScaleAndOffset()
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        results?.let { poseLandmarkerResult ->
            if (poseLandmarkerResult.landmarks().isNotEmpty()) {
                val landmarks = poseLandmarkerResult.landmarks()[0]
                val maxSize = landmarks.size

                var i = 0
                while (i < BODY_POINTS.size) {
                    val pointIndex = BODY_POINTS[i]
                    if (pointIndex < maxSize) {
                        val landmark = landmarks[pointIndex]
                        canvas.drawPoint(
                            landmark.x() * imageWidth * scaleFactor,
                            landmark.y() * imageHeight * scaleFactor,
                            pointPaint
                        )
                    }
                    i++
                }

                i = 0
                while (i < BODY_CONNECTIONS.size) {
                    val connection = BODY_CONNECTIONS[i]
                    val startIdx = connection[0]
                    val endIdx = connection[1]

                    if (startIdx < maxSize && endIdx < maxSize) {
                        val startLandmark = landmarks[startIdx]
                        val endLandmark = landmarks[endIdx]

                        canvas.drawLine(
                            startLandmark.x() * imageWidth * scaleFactor,
                            startLandmark.y() * imageHeight * scaleFactor,
                            endLandmark.x() * imageWidth * scaleFactor,
                            endLandmark.y() * imageHeight * scaleFactor,
                            linePaint
                        )
                    }
                    i++
                }
            }
        }
        if (showErrorFlash) {
            val elapsed = System.currentTimeMillis() - errorFlashStartTime
            if (elapsed < 1000) {
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), errorFlashPaint)
                invalidate()
            } else {
                showErrorFlash = false
            }
        }
    }

    fun flashError() {
        showErrorFlash = true
        errorFlashStartTime = System.currentTimeMillis()
        invalidate()
    }

    fun setResults(
        poseLandmarkerResults: PoseLandmarkerResult,
        imageHeight: Int,
        imageWidth: Int,
        runningMode: RunningMode = RunningMode.LIVE_STREAM
    ) {
        val currentTime = System.currentTimeMillis()
        frameCount++

        if (lastUpdateTime > 0) {
            val timeSinceLastUpdate = currentTime - lastUpdateTime
        }

        lastUpdateTime = currentTime

        Log.v("PoseOverlay", "Frame #$frameCount - imageSize: ${imageWidth}x$imageHeight")

        results = poseLandmarkerResults
        this.imageHeight = imageHeight
        this.imageWidth = imageWidth

        calculateScaleAndOffset()

        invalidate()
    }

    companion object {
        private const val LANDMARK_STROKE_WIDTH = 12F

        private val BODY_POINTS = intArrayOf(11, 12, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32)

        private val BODY_CONNECTIONS = arrayOf(
            intArrayOf(11, 12), intArrayOf(11, 23), intArrayOf(12, 24), intArrayOf(23, 24),
            intArrayOf(23, 25), intArrayOf(25, 27), intArrayOf(27, 29), intArrayOf(27, 31), intArrayOf(29, 31),
            intArrayOf(24, 26), intArrayOf(26, 28), intArrayOf(28, 30), intArrayOf(28, 32), intArrayOf(30, 32)
        )
    }
}