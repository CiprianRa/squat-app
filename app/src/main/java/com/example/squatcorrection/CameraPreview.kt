package com.example.squatcorrection

import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
    val previewUseCase = remember { androidx.camera.core.Preview.Builder().build() }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    val imageAnalysisUseCase = remember {
        ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(720,1280),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }

    fun rebindCameraProvider() {
        cameraProvider?.let { provider ->
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            provider.unbindAll()

            Log.d("CameraPreview", "Rebinding camera with lensFacing: $lensFacing")

            onImageAnalysis?.let { analyzer ->
                Log.d("CameraPreview", "Setting up image analyzer")
                imageAnalysisUseCase.setAnalyzer(
                    Executors.newSingleThreadExecutor()
                ) { imageProxy ->
                    analyzer(imageProxy)
                    imageProxy.close()
                }
            }

            val useCases = if (onImageAnalysis != null) {
                arrayOf(previewUseCase, imageAnalysisUseCase)
            } else {
                arrayOf(previewUseCase)
            }

            Log.d("CameraPreview", "Binding ${useCases.size} use cases")

            provider.bindToLifecycle(
                localLifecycleOwner,
                cameraSelector,
                *useCases
            )
        }
    }

    LaunchedEffect(Unit) {
        cameraProvider = ProcessCameraProvider.awaitInstance(localContext)
        rebindCameraProvider()
    }

    LaunchedEffect(lensFacing) {
        rebindCameraProvider()
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            PreviewView(context).also { previewView ->
                previewUseCase.surfaceProvider = previewView.surfaceProvider
            }
        }
    )
}