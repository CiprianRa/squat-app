package com.example.squatcorrection

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import java.io.IOException

class AudioFeedback(private val context: Context) {

    companion object {
        private const val TAG = "AudioFeedback"
    }

    private var mediaPlayer: MediaPlayer? = null

    fun playKneeError() {
        playSound("Genunchi.mp3")
    }

    fun playDepthError() {
        playSound("Adancime.mp3")
    }

    fun playHeelError() {
        playSound("Calcaie.mp3")
    }

    fun playCorrectSquat() {
        playSound("Corecte.mp3")
    }

    private fun playSound(fileName: String) {
        try {
            stopSound()

            mediaPlayer = MediaPlayer()

            val assetFileDescriptor = context.assets.openFd(fileName)
            mediaPlayer?.setDataSource(
                assetFileDescriptor.fileDescriptor,
                assetFileDescriptor.startOffset,
                assetFileDescriptor.length
            )
            assetFileDescriptor.close()

            mediaPlayer?.prepare()

            mediaPlayer?.setOnCompletionListener { player ->
                player.release()
                mediaPlayer = null
                Log.d(TAG, "Sunet terminat: $fileName")
            }

            mediaPlayer?.setOnErrorListener { player, what, extra ->
                Log.e(TAG, "Eroare MediaPlayer pentru $fileName: what=$what, extra=$extra")
                player.release()
                mediaPlayer = null
                true
            }

            mediaPlayer?.start()
            Log.d(TAG, "Redand sunet: $fileName")

        } catch (e: IOException) {
            Log.e(TAG, "Eroare la încarcarea fisierului $fileName: ${e.message}")
            releaseMediaPlayer()
        } catch (e: Exception) {
            Log.e(TAG, "Eroare la redarea sunetului $fileName: ${e.message}")
            releaseMediaPlayer()
        }
    }

    fun stopSound() {
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
                mediaPlayer = null
                Log.d(TAG, "MediaPlayer oprit")
            } catch (e: Exception) {
                Log.e(TAG, "Eroare la oprirea sunetului: ${e.message}")
            }
        }
    }

    private fun releaseMediaPlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
    }


    fun release() {
        stopSound()
        Log.d(TAG, "AudioFeedback resources released")
    }
}