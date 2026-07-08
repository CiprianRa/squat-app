package com.example.squatcorrection

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.concurrent.Executors

@Composable
fun CameraAppScreen(onBackToMenu: (() -> Unit)? = null) {
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    val context = LocalContext.current

    val activity = context as? ComponentActivity

    val poseOverlay = remember { PoseOverlay(context) }

    val squatAnalyzer = remember { SquatAnalyzer(context,poseOverlay) }

    val (currentPhase, setCurrentPhase) = remember { mutableStateOf("WAITING") }
    val (lastPrediction, setLastPrediction) = remember { mutableStateOf("") }
    val (warmupTime, setWarmupTime) = remember { mutableStateOf(0) }
    val (correctCount, setCorrectCount) = remember { mutableIntStateOf(0) }
    val poseDetector = remember {
        PoseDetector(
            context = context,
            resultListener = object : PoseDetector.PoseResultListener {
                override fun onResults(result: PoseDetector.ResultBundle) {
                    activity?.runOnUiThread {
                        if (result.results.isNotEmpty()) {
                            Log.v("PoseResults", "POSE DETECTED: To overlay and analyzer")

                            poseOverlay.setResults(
                                result.results[0],
                                result.inputImageHeight,
                                result.inputImageWidth,
                                RunningMode.LIVE_STREAM
                            )

                            squatAnalyzer.processLandmarks(result.results[0])

                            setCurrentPhase(squatAnalyzer.getCurrentPhase())

                            val currentPrediction = squatAnalyzer.lastPrediction
                            if (currentPrediction != lastPrediction) {
                                Log.d("UI_UPDATE", "NEW PREDICTION: '$currentPrediction'")
                                setLastPrediction(currentPrediction)
                            }

                            setWarmupTime(squatAnalyzer.getRemainingWarmupTime())
                            setCorrectCount(squatAnalyzer.getCorrectSquatsCount())

                        } else {
                            Log.d("PoseResults", "NO RESULTS: MediaPipe returned empty")
                            poseOverlay.clear()
                        }
                    }
                }

                override fun onError(error: String, errorCode: Int) {
                    Log.e("PoseResults", "ERROR: $error")
                    activity?.runOnUiThread {
                        poseOverlay.clear()
                    }
                }
            }
        )
    }

    DisposableEffect(Unit) {
        Log.d("CameraAppScreen", "Starting pose detector initialization...")
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            val success = poseDetector.setupPoseLandmarker()
            Log.d("CameraAppScreen", "Setup result: $success")
        }

        onDispose {
            Log.d("CameraAppScreen", "Closing pose detector...")
            poseDetector.close()
            squatAnalyzer.close()
            executor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(
            lensFacing = lensFacing,
            modifier = Modifier.fillMaxSize(),
            onImageAnalysis = { imageProxy ->
                Log.v("CameraAppScreen", "Frame received: ${imageProxy.width}x${imageProxy.height}")
                poseDetector.detectLiveStream(
                    imageProxy = imageProxy,
                    isFrontCamera = lensFacing == CameraSelector.LENS_FACING_FRONT
                )
            }
        )

        AndroidView(
            factory = { poseOverlay },
            modifier = Modifier.fillMaxSize()
        )
        Text(
            text = "$correctCount",
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(32.dp),
            color = androidx.compose.ui.graphics.Color.Green,
            style = androidx.compose.material3.MaterialTheme.typography.displayLarge
        )
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 50.dp)
        ) {
            if (warmupTime > 0) {
                Text(
                    text = "Pozitioneaza-te in cadru... ($warmupTime)",
                    color = androidx.compose.ui.graphics.Color.Yellow,
                    style = androidx.compose.material3.MaterialTheme.typography.headlineMedium
                )
            } else {
                Text(
                    text = "Phase: $currentPhase",
                    color = androidx.compose.ui.graphics.Color.White,
                    style = androidx.compose.material3.MaterialTheme.typography.headlineMedium
                )

                Text(
                    text = if (lastPrediction.isNotEmpty()) {
                        "TFLite: $lastPrediction"
                    } else {
                        "TFLite: Astept predictie..."
                    },
                    modifier = Modifier.padding(top = 8.dp),
                    color = when (lastPrediction) {
                        "Corecte" -> androidx.compose.ui.graphics.Color.Green
                        "Genunchi" -> androidx.compose.ui.graphics.Color.Red
                        "Adancime" -> androidx.compose.ui.graphics.Color.Blue
                        "Calcaie" -> androidx.compose.ui.graphics.Color.Magenta
                        else -> androidx.compose.ui.graphics.Color.Gray
                    },
                    style = androidx.compose.material3.MaterialTheme.typography.headlineSmall
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        ) {
            Button(
                onClick = {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                        CameraSelector.LENS_FACING_BACK
                    } else {
                        CameraSelector.LENS_FACING_FRONT
                    }
                }
            ) {
                Text(
                    text = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                        "Schimba pe Camera Spate"
                    } else {
                        "Schimba pe Camera Fata"
                    }
                )
            }
            onBackToMenu?.let { backAction ->
                Button(onClick = backAction) {
                    Text("Inapoi la Meniu")
                }
            }
        }
    }
}