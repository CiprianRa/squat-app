package com.example.squatcorrection

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CameraAppScreen() {
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(
            lensFacing = lensFacing,
            modifier = Modifier.fillMaxSize(),
            onImageAnalysis = { imageProxy ->
                Log.d("CameraAnalysis", "Frame: ${imageProxy.width}x${imageProxy.height}")
                imageProxy.close()}
        )

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
                        "Switch to Back Camera"
                    } else {
                        "Switch to Front Camera"
                    }
                )
            }
        }
    }
}