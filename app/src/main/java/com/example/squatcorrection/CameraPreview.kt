package com.example.squatcorrection

import android.util.Log
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors

@Composable
fun CameraPreview(
    lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    modifier: Modifier = Modifier,
    onImageAnalysis: ((ImageProxy) -> Unit)? = null
) {
    val localContext = LocalContext.current
    val localLifecycleOwner = LocalLifecycleOwner.current
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    val backgroundExecutor = remember { Executors.newSingleThreadExecutor() }

    val previewUseCase = remember {
        Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .build()
    }

    val imageAnalysisUseCase = remember {
        ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
    }

    fun rebindCameraProvider() {
        cameraProvider?.let { provider ->
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            provider.unbindAll()

            Log.d("CameraPreview", "Binding camera with MediaPipe analysis...")

            onImageAnalysis?.let { analyzer ->
                imageAnalysisUseCase.setAnalyzer(backgroundExecutor) { imageProxy ->
                    analyzer(imageProxy)
                }
            }

            try {

                val camera = provider.bindToLifecycle(
                    localLifecycleOwner,
                    cameraSelector,
                    previewUseCase,
                    imageAnalysisUseCase
                )

                Log.d("CameraPreview", "Camera bound successfully for MediaPipe")
            } catch (exc: Exception) {
                Log.e("CameraPreview", "Camera binding failed", exc)
            }
        }
    }

    LaunchedEffect(Unit) {
        Log.d("CameraPreview", "Getting camera provider...")
        cameraProvider = ProcessCameraProvider.awaitInstance(localContext)
        rebindCameraProvider()
    }

    LaunchedEffect(lensFacing) {
        Log.d("CameraPreview", "Rebinding for lens: $lensFacing")
        rebindCameraProvider()
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            PreviewView(context).also { previewView ->
                previewView.scaleType = PreviewView.ScaleType.FILL_START

                previewUseCase.surfaceProvider = previewView.surfaceProvider
                Log.d("CameraPreview", "Surface provider attached with FILL_START mode")
            }
        }
    )
}