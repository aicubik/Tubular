package org.schabi.newpipe.player.translation

import kotlinx.coroutines.runBlocking
import org.junit.Test

class YandexTest {

    @Test
    fun testTranslation() = runBlocking {
        val service = YandexTranslationService()
        var completed = false
        val job = service.translateVideo(
            videoUrl = "https://youtu.be/dQw4w9WgXcQ",
            durationSeconds = 212.0,
            videoTitle = "Rick Astley - Never Gonna Give You Up",
            fromLanguage = "en",
            toLanguage = "ru",
            callback = object : YandexTranslationService.TranslationCallback {
                override fun onProgress(status: String, remainingSeconds: Int) {
                    println("Progress: $status, remaining: $remainingSeconds")
                }

                override fun onSuccess(audioUrl: String, durationSeconds: Double) {
                    println("Success! URL: $audioUrl")
                    completed = true
                }

                override fun onError(error: String) {
                    println("Error: $error")
                    completed = true
                }
            }
        )

        while (!completed) {
            kotlinx.coroutines.delay(1000)
        }
        job.cancel()
    }
}
