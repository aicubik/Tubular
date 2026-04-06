package org.schabi.newpipe.player.translation

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Service for translating YouTube videos via Yandex Browser's internal API.
 *
 * Based on reverse-engineered protocol from:
 * - https://github.com/tskau/vtrans
 * - https://github.com/ilyhalight/voice-over-translation
 */
class YandexTranslationService {

    companion object {
        private const val TAG = "YandexTranslation"

        // HMAC key extracted from Yandex Browser (via VOT / vtrans community)
        private const val HMAC_KEY = "xtGCyGdTY2Jy6OMEKdTuXev3Twhkamgm"

        // Yandex Browser API endpoint
        private const val API_BASE_URL = "https://api.browser.yandex.ru"
        private const val TRANSLATE_PATH = "/video-translation/translate"

        // User-Agent string mimicking Yandex Browser
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 YaBrowser/24.1.5.825 " +
                "Yowser/2.5 Safari/537.36"

        // Default video duration hint (seconds) if unknown
        private const val DEFAULT_DURATION = 900

        // Polling settings
        private const val POLL_INTERVAL_MS = 5000L
        private const val MAX_POLL_ATTEMPTS = 60 // 5 minutes max
    }

    /**
     * Result of a translation request.
     */
    sealed class TranslationResult {
        data class Success(val audioUrl: String, val duration: Double) : TranslationResult()
        data class InProgress(val remainingTime: Int) : TranslationResult()
        data class Error(val message: String) : TranslationResult()
    }

    /**
     * Callback interface for translation progress updates.
     */
    interface TranslationCallback {
        fun onProgress(status: String, remainingSeconds: Int)
        fun onSuccess(audioUrl: String, durationSeconds: Double)
        fun onError(error: String)
    }

    // Session ID for "live voices" quality (set from user's Yandex cookie)
    private var sessionId: String? = null

    fun setSessionId(sid: String) {
        sessionId = sid
    }

    /**
     * Translate a YouTube video and return the MP3 URL via callback.
     * This handles polling automatically.
     */
    fun translateVideo(
        videoUrl: String,
        fromLanguage: String = "en",
        toLanguage: String = "ru",
        callback: TranslationCallback,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
    ): Job {
        return scope.launch {
            try {
                val shortUrl = shortenYouTubeUrl(videoUrl)
                var attempts = 0

                while (attempts < MAX_POLL_ATTEMPTS) {
                    val result = makeTranslationRequest(
                        originalUrl = shortUrl,
                        fromLang = fromLanguage,
                        toLang = toLanguage
                    )

                    when (result) {
                        is TranslationResult.Success -> {
                            withContext(Dispatchers.Main) {
                                callback.onSuccess(result.audioUrl, result.duration)
                            }
                            return@launch
                        }

                        is TranslationResult.InProgress -> {
                            withContext(Dispatchers.Main) {
                                callback.onProgress(
                                    "Processing...",
                                    result.remainingTime
                                )
                            }
                            delay(POLL_INTERVAL_MS)
                            attempts++
                        }

                        is TranslationResult.Error -> {
                            withContext(Dispatchers.Main) {
                                callback.onError(result.message)
                            }
                            return@launch
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    callback.onError("Translation timed out after ${MAX_POLL_ATTEMPTS * POLL_INTERVAL_MS / 1000} seconds")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Translation failed", e)
                withContext(Dispatchers.Main) {
                    callback.onError("Translation error: ${e.message}")
                }
            }
        }
    }

    /**
     * Make a single translation request to Yandex API.
     */
    private fun makeTranslationRequest(
        originalUrl: String,
        fromLang: String,
        toLang: String
    ): TranslationResult {
        // 1. Build protobuf request body
        val requestBody = buildProtobufRequest(originalUrl, fromLang, toLang)

        // 2. Sign the body with HMAC-SHA256
        val signature = signBody(requestBody)

        // 3. Generate a random token
        val token = UUID.randomUUID().toString()

        // 4. Make the HTTP request
        val url = URL("$API_BASE_URL$TRANSLATE_PATH")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/x-protobuf")
                setRequestProperty("Content-Type", "application/x-protobuf")
                setRequestProperty("Accept-Language", "en")
                setRequestProperty("Pragma", "no-cache")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Sec-Fetch-Mode", "no-cors")
                setRequestProperty("vtrans-signature", signature)
                setRequestProperty("sec-vtrans-token", token)

                // Add session ID for "live voices" if available
                sessionId?.let {
                    setRequestProperty("Cookie", "Session_id=$it")
                }
            }

            // Write request body
            connection.outputStream.use { it.write(requestBody) }

            // Read response
            val responseCode = connection.responseCode
            if (responseCode != 200) {
                return TranslationResult.Error("HTTP error: $responseCode")
            }

            val responseBytes = connection.inputStream.use { it.readBytes() }
            return parseProtobufResponse(responseBytes)
        } finally {
            connection.disconnect()
        }
    }

    // =========================================================================
    // Protobuf encoding/decoding (manual, without protobuf library dependency)
    // =========================================================================

    /**
     * Build a protobuf-encoded VideoTranslationRequest.
     *
     * Proto schema (reconstructed):
     *   message VideoTranslationRequest {
     *     string originalUrl = 3;
     *     string originalLanguage = 4;
     *     int32  originalDuration = 5;
     *     string translationLanguage = 6;
     *     bool   isFirstRequest = 7;
     *   }
     */
    private fun buildProtobufRequest(
        originalUrl: String,
        fromLang: String,
        toLang: String,
        duration: Int = DEFAULT_DURATION
    ): ByteArray {
        val baos = ByteArrayOutputStream()

        // Field 3: originalUrl (wire type 2 = length-delimited)
        writeProtobufString(baos, fieldNumber = 3, value = originalUrl)
        // Field 4: originalLanguage
        writeProtobufString(baos, fieldNumber = 4, value = fromLang)
        // Field 5: originalDuration (wire type 0 = varint)
        writeProtobufVarint(baos, fieldNumber = 5, value = duration)
        // Field 6: translationLanguage
        writeProtobufString(baos, fieldNumber = 6, value = toLang)
        // Field 7: isFirstRequest (bool, wire type 0)
        writeProtobufVarint(baos, fieldNumber = 7, value = 1) // true

        return baos.toByteArray()
    }

    /**
     * Parse a protobuf-encoded VideoTranslationResponse.
     *
     * Proto schema (reconstructed):
     *   message VideoTranslationResponse {
     *     int32  responseStatus = 1;   // 0=ERROR, 1=SUCCESS, 2=WORK_IN_PROGRESS
     *     string translationUrl = 2;
     *     double translationDuration = 4;
     *     int32  remainingTime = 5;
     *     string responseMessage = 9;
     *   }
     */
    private fun parseProtobufResponse(data: ByteArray): TranslationResult {
        var status = -1
        var translationUrl: String? = null
        var translationDuration = 0.0
        var remainingTime = 0
        var errorMessage: String? = null

        var offset = 0
        while (offset < data.size) {
            val (tag, newOffset1) = readVarint(data, offset)
            offset = newOffset1

            val fieldNumber = (tag shr 3).toInt()
            val wireType = (tag and 0x07).toInt()

            when (wireType) {
                0 -> { // Varint
                    val (value, newOffset2) = readVarint(data, offset)
                    offset = newOffset2
                    when (fieldNumber) {
                        1 -> status = value.toInt()
                        5 -> remainingTime = value.toInt()
                    }
                }

                1 -> { // 64-bit (double)
                    if (offset + 8 <= data.size) {
                        val bits = java.lang.Long.reverseBytes(
                            java.nio.ByteBuffer.wrap(data, offset, 8).long
                        )
                        val doubleVal = java.lang.Double.longBitsToDouble(bits)
                        offset += 8
                        when (fieldNumber) {
                            4 -> translationDuration = doubleVal
                        }
                    }
                }

                2 -> { // Length-delimited (string/bytes)
                    val (length, newOffset3) = readVarint(data, offset)
                    offset = newOffset3
                    val strBytes = data.copyOfRange(offset, offset + length.toInt())
                    offset += length.toInt()
                    when (fieldNumber) {
                        2 -> translationUrl = String(strBytes, Charsets.UTF_8)
                        9 -> errorMessage = String(strBytes, Charsets.UTF_8)
                    }
                }

                5 -> { // 32-bit
                    offset += 4
                }
            }
        }

        return when (status) {
            0 -> TranslationResult.Error(errorMessage ?: "Unknown error from Yandex")

            1 -> {
                if (translationUrl != null) {
                    TranslationResult.Success(translationUrl, translationDuration)
                } else {
                    TranslationResult.Error("Success status but no translation URL")
                }
            }

            2 -> TranslationResult.InProgress(remainingTime)

            else -> TranslationResult.Error("Unknown response status: $status")
        }
    }

    // =========================================================================
    // Protobuf helpers
    // =========================================================================

    private fun writeProtobufString(out: ByteArrayOutputStream, fieldNumber: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val tag = (fieldNumber shl 3) or 2 // wire type 2
        writeVarint(out, tag)
        writeVarint(out, bytes.size)
        out.write(bytes)
    }

    private fun writeProtobufVarint(out: ByteArrayOutputStream, fieldNumber: Int, value: Int) {
        val tag = (fieldNumber shl 3) or 0 // wire type 0
        writeVarint(out, tag)
        writeVarint(out, value)
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v > 0x7F) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v and 0x7F)
    }

    private fun readVarint(data: ByteArray, startOffset: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var offset = startOffset
        while (offset < data.size) {
            val b = data[offset].toInt() and 0xFF
            offset++
            result = result or ((b.toLong() and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return Pair(result, offset)
    }

    // =========================================================================
    // HMAC signing
    // =========================================================================

    private fun signBody(body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(HMAC_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(keySpec)
        val hash = mac.doFinal(body)
        return hash.joinToString("") { "%02x".format(it) }
    }

    // =========================================================================
    // URL shortener (YouTube specific)
    // =========================================================================

    private fun shortenYouTubeUrl(url: String): String {
        return try {
            val parsed = URL(url)
            if (parsed.host.contains("youtube.com")) {
                val videoId = parsed.query
                    ?.split("&")
                    ?.firstOrNull { it.startsWith("v=") }
                    ?.substringAfter("v=")

                if (videoId != null) {
                    "https://youtu.be/$videoId"
                } else {
                    url
                }
            } else if (parsed.host.contains("youtu.be")) {
                url // already short
            } else {
                url
            }
        } catch (e: Exception) {
            url
        }
    }
}
