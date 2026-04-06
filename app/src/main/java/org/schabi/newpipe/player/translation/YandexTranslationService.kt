package org.schabi.newpipe.player.translation

import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
 * Includes modifications based on decompiled successful implementations.
 */
class YandexTranslationService {

    companion object {
        private const val TAG = "YandexTranslation"

        // Working HMAC key extracted from Orfeev's NewPipe build
        private const val HMAC_KEY = "bt8xH3VOlb4mqf0nqAibnDOoiPlXsisf"

        // Yandex Browser API endpoint
        private const val API_URL =
            "https://api.browser.yandex.ru/video-translation/translate"

        // User-Agent string from Orfeev's implementation
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/134.0.0.0 YaBrowser/25.4.0.0 Safari/537.36"

        // Polling settings
        private const val POLL_INTERVAL_MS = 5000L
        private const val MAX_POLL_ATTEMPTS = 60
    }

    sealed class TranslationResult {
        data class Success(
            val audioUrl: String,
            val duration: Double
        ) : TranslationResult()
        data class InProgress(
            val remainingTime: Int
        ) : TranslationResult()
        data class Error(
            val message: String
        ) : TranslationResult()
    }

    interface TranslationCallback {
        fun onProgress(status: String, remainingSeconds: Int)
        fun onSuccess(audioUrl: String, durationSeconds: Double)
        fun onError(error: String)
    }

    fun translateVideo(
        videoUrl: String,
        durationSeconds: Double,
        fromLanguage: String = "en",
        toLanguage: String = "ru",
        callback: TranslationCallback,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
    ): Job {
        return scope.launch {
            try {
                Log.d(TAG, "Starting translation for: $videoUrl")
                var attempts = 0

                while (attempts < MAX_POLL_ATTEMPTS) {
                    val result = makeTranslationRequest(
                        videoUrl = videoUrl,
                        duration = durationSeconds,
                        fromLang = fromLanguage,
                        toLang = toLanguage
                    )

                    when (result) {
                        is TranslationResult.Success -> {
                            withContext(Dispatchers.Main) {
                                callback.onSuccess(
                                    result.audioUrl,
                                    result.duration
                                )
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
                    callback.onError(
                        "Translation timed out after " +
                            "${MAX_POLL_ATTEMPTS * POLL_INTERVAL_MS / 1000}s"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Translation failed", e)
                withContext(Dispatchers.Main) {
                    callback.onError(
                        "Translation error: ${e.message}"
                    )
                }
            }
        }
    }

    private fun makeTranslationRequest(
        videoUrl: String,
        duration: Double,
        fromLang: String,
        toLang: String
    ): TranslationResult {
        val requestBody = buildProtobufRequest(videoUrl, duration, fromLang, toLang)
        val signature = signBody(requestBody)

        Log.d(
            TAG,
            "Request: url=$videoUrl, from=$fromLang, to=$toLang, " +
                "bodySize=${requestBody.size}"
        )

        val url = URL(API_URL)
        val conn = url.openConnection() as HttpURLConnection

        try {
            conn.apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Accept", "application/x-protobuf")
                setRequestProperty("Content-Type", "application/x-protobuf")
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Vtrans-Signature", signature)
            }

            conn.outputStream.use { it.write(requestBody) }

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val errBody = try {
                    conn.errorStream?.use { String(it.readBytes()) } ?: "no body"
                } catch (_: Exception) {
                    "unreadable"
                }
                Log.e(TAG, "HTTP $responseCode, error body: $errBody")
                return TranslationResult.Error("HTTP error: $responseCode")
            }

            val responseBytes = conn.inputStream.use { it.readBytes() }
            val hexString = responseBytes.joinToString("") { "%02X".format(it) }
            Log.d(TAG, "Response size: ${responseBytes.size} bytes. Hex: $hexString")
            return parseProtobufResponse(responseBytes)
        } finally {
            conn.disconnect()
        }
    }

    // =================================================================
    // Protobuf encoding (matching Orfeev's implementation)
    // =================================================================

    private fun buildProtobufRequest(
        videoUrl: String,
        duration: Double,
        fromLang: String,
        toLang: String
    ): ByteArray {
        val baos = ByteArrayOutputStream()

        // Field 3: url
        writeString(baos, 3, videoUrl)
        // Field 5: firstRequest
        writeVarintField(baos, 5, 1) // true
        // Field 6: duration
        writeDouble(baos, 6, duration)
        // Field 7: unknown0
        writeVarintField(baos, 7, 1)
        // Field 8: language (source)
        writeString(baos, 8, fromLang)
        // Field 10: unknown1
        writeVarintField(baos, 10, 0)
        // Field 14: responseLanguage (target)
        writeString(baos, 14, toLang)
        // Field 15: unknown2
        writeVarintField(baos, 15, 1)
        // Field 16: unknown3
        writeVarintField(baos, 16, 2)

        return baos.toByteArray()
    }

    // =================================================================
    // Protobuf decoding
    // =================================================================

    private fun parseProtobufResponse(data: ByteArray): TranslationResult {
        var translationUrl: String? = null
        var translationDuration = 0.0
        var status = -1
        var remainingTime = 0
        var errorMessage: String? = null

        var offset = 0
        while (offset < data.size) {
            val (tag, newOff1) = readVarint(data, offset)
            offset = newOff1

            val fieldNumber = (tag shr 3).toInt()
            val wireType = (tag and 0x07).toInt()

            when (wireType) {
                0 -> { // Varint
                    val (value, newOff2) = readVarint(data, offset)
                    offset = newOff2
                    when (fieldNumber) {
                        4 -> status = value.toInt()
                        5 -> remainingTime = value.toInt()
                    }
                }

                1 -> { // 64-bit (double)
                    if (offset + 8 <= data.size) {
                        val bb = ByteBuffer.wrap(data, offset, 8).order(ByteOrder.LITTLE_ENDIAN)
                        val doubleVal = bb.double
                        offset += 8
                        when (fieldNumber) {
                            2 -> translationDuration = doubleVal
                        }
                    }
                }

                2 -> { // Length-delimited (string/bytes)
                    val (length, newOff3) = readVarint(data, offset)
                    offset = newOff3
                    val len = length.toInt()
                    if (offset + len <= data.size) {
                        val str = String(data, offset, len, Charsets.UTF_8)
                        when (fieldNumber) {
                            1 -> translationUrl = str
                            9 -> errorMessage = str
                        }
                    }
                    offset += len
                }

                5 -> { // 32-bit
                    offset += 4
                }

                else -> {
                    break
                }
            }
        }

        Log.d(
            TAG,
            "Parsed response: status=$status, " +
                "url=${translationUrl?.take(50)}, " +
                "duration=$translationDuration, " +
                "remaining=$remainingTime, " +
                "error=$errorMessage"
        )

        return when (status) {
            0 -> TranslationResult.Error(errorMessage ?: "Translation failed (status 0)")

            1, 2 -> {
                if (translationUrl != null) {
                    TranslationResult.Success(translationUrl, translationDuration)
                } else {
                    TranslationResult.InProgress(remainingTime) // May get status 1/2 but URL not ready
                }
            }

            3, 6 -> TranslationResult.InProgress(remainingTime)

            else -> TranslationResult.Error("Unknown status: $status")
        }
    }

    // =================================================================
    // Protobuf writing/reading helpers
    // =================================================================

    private fun writeString(out: ByteArrayOutputStream, fieldNumber: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val tag = (fieldNumber shl 3) or 2
        writeVarint(out, tag)
        writeVarint(out, bytes.size)
        out.write(bytes)
    }

    private fun writeVarintField(out: ByteArrayOutputStream, fieldNumber: Int, value: Int) {
        val tag = (fieldNumber shl 3) or 0
        writeVarint(out, tag)
        writeVarint(out, value)
    }

    private fun writeDouble(out: ByteArrayOutputStream, fieldNumber: Int, value: Double) {
        val tag = (fieldNumber shl 3) or 1
        writeVarint(out, tag)
        val bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        bb.putDouble(value)
        out.write(bb.array())
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

    // =================================================================
    // HMAC signing
    // =================================================================

    private fun signBody(body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(HMAC_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(keySpec)
        val hash = mac.doFinal(body)
        return hash.joinToString("") { "%02x".format(it) }
    }

    // =================================================================
    // URL shortener
    // =================================================================

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
            } else {
                url
            }
        } catch (e: Exception) {
            url
        }
    }
}
