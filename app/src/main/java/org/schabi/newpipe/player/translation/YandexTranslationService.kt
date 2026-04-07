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
 * Fully aligned with FOSWLY/vot.js (Voice Over Translation) library.
 *
 * Flow:
 * 1. Create session via /session/create (get secretKey + uuid)
 * 2. Build protobuf request body
 * 3. Sign with Vtrans-Signature, Sec-Vtrans-Sk, Sec-Vtrans-Token
 * 4. POST to /video-translation/translate
 */
class YandexTranslationService {

    companion object {
        private const val TAG = "YandexTranslation"

        // HMAC key from VOT config
        private const val HMAC_KEY = "bt8xH3VOlb4mqf0nqAibnDOoiPlXsisf"

        // Base API host
        private const val API_HOST = "https://api.browser.yandex.ru"

        // Paths (matching vot.js)
        private const val SESSION_PATH = "/session/create"
        private const val TRANSLATE_PATH = "/video-translation/translate"

        // User-Agent matching vot.js config
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/146.0.0.0 YaBrowser/26.3.1.981 " +
                "Yowser/2.5 Safari/537.36"

        // Component version matching vot.js config
        private const val COMPONENT_VERSION = "26.3.1.981"

        private const val POLL_INTERVAL_MS = 5000L
        private const val MAX_POLL_ATTEMPTS = 60

        // User's OAuth session ID for "Живой голос"
        private const val SESSION_ID = ""
    }

    // --- Session state ---
    private data class VtransSession(
        val uuid: String,
        val secretKey: String,
        val expires: Long,
        val createdAt: Long
    )

    @Volatile
    private var currentSession: VtransSession? = null

    sealed class TranslationResult {
        data class Success(
            val audioUrl: String,
            val duration: Double
        ) : TranslationResult()
        data class InProgress(
            val remainingTime: Int,
            val status: Int,
            val translationId: String?
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
        videoTitle: String,
        fromLanguage: String = "en",
        toLanguage: String = "ru",
        callback: TranslationCallback,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
    ): Job {
        return scope.launch {
            try {
                val shortenedUrl = shortenYouTubeUrl(videoUrl)
                Log.d(TAG, "Starting translation for: $shortenedUrl ($videoTitle)")
                var attempts = 0
                var hasSentFailAudio = false

                while (attempts < MAX_POLL_ATTEMPTS) {
                    val result = makeTranslationRequest(
                        videoUrl = shortenedUrl,
                        duration = durationSeconds,
                        videoTitle = videoTitle,
                        fromLang = fromLanguage,
                        toLang = toLanguage,
                        firstRequest = (attempts == 0)
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
                                callback.onProgress("Processing...", result.remainingTime)
                            }

                            // Handle AUDIO_REQUESTED (6) by sending fake audio requests (fallback)
                            if (result.status == 6 && !hasSentFailAudio) {
                                hasSentFailAudio = true
                                Log.d(TAG, "Status 6 (AUDIO_REQUESTED) - executing fallback...")
                                val session = getOrCreateSession()
                                requestFailAudio(session, shortenedUrl)
                                if (result.translationId != null) {
                                    requestAudioEmpty(session, shortenedUrl, result.translationId)
                                }
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
                    val seconds = MAX_POLL_ATTEMPTS * POLL_INTERVAL_MS / 1000
                    callback.onError("Translation timed out after ${seconds}s")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Translation failed", e)
                withContext(Dispatchers.Main) {
                    callback.onError("Translation error: ${e.message}")
                }
            }
        }
    }

    // =========================================================================
    // Session management (matches vot.js MinimalClient.getSession/createSession)
    // =========================================================================

    @Synchronized
    private fun getOrCreateSession(): VtransSession {
        val session = currentSession
        val now = System.currentTimeMillis() / 1000
        if (session != null && session.createdAt + session.expires > now) {
            return session
        }

        Log.d(TAG, "Creating new Vtrans session...")
        val newSession = createSession()
        currentSession = newSession
        Log.d(TAG, "Session created: uuid=${newSession.uuid}, expires=${newSession.expires}")
        return newSession
    }

    private fun createSession(): VtransSession {
        val uuid = generateUUID()
        val body = encodeSessionRequest(uuid, "video-translation")
        val signature = hmacSign(body)

        val url = URL("$API_HOST$SESSION_PATH")
        val conn = url.openConnection() as HttpURLConnection

        try {
            conn.apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Content-Type", "application/x-protobuf")
                setRequestProperty("Accept", "application/x-protobuf")
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept-Language", "en")
                setRequestProperty("Pragma", "no-cache")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Vtrans-Signature", signature)
                if (SESSION_ID.isNotEmpty()) {
                    setRequestProperty("Cookie", "Session_id=$SESSION_ID")
                }
            }

            conn.outputStream.use { it.write(body) }

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val errBody = try {
                    conn.errorStream?.use { String(it.readBytes()) } ?: "no body"
                } catch (_: Exception) {
                    "unreadable"
                }
                Log.e(TAG, "Session create failed: HTTP $responseCode, $errBody")
                throw RuntimeException("Session create failed: HTTP $responseCode")
            }

            val responseBytes = conn.inputStream.use { it.readBytes() }
            return decodeSessionResponse(responseBytes, uuid)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Encode session request protobuf:
     * field 1 (string): uuid
     * field 2 (string): module
     */
    private fun encodeSessionRequest(uuid: String, module: String): ByteArray {
        val baos = ByteArrayOutputStream()
        writeString(baos, 1, uuid)
        writeString(baos, 2, module)
        return baos.toByteArray()
    }

    /**
     * Decode session response protobuf:
     * field 1 (string): secretKey
     * field 2 (varint): expires
     */
    private fun decodeSessionResponse(data: ByteArray, uuid: String): VtransSession {
        var secretKey = ""
        var expires = 0L
        var offset = 0

        try {
            while (offset < data.size) {
                val (tag, newOff1) = readVarint(data, offset)
                offset = newOff1
                val fieldNumber = (tag shr 3).toInt()
                val wireType = (tag and 0x07).toInt()

                when (wireType) {
                    0 -> {
                        val (value, newOff2) = readVarint(data, offset)
                        offset = newOff2
                        if (fieldNumber == 2) expires = value
                    }

                    2 -> {
                        val (len, newOff3) = readVarint(data, offset)
                        offset = newOff3
                        val length = len.toInt()
                        if (offset + length <= data.size) {
                            val str = String(data, offset, length, Charsets.UTF_8)
                            if (fieldNumber == 1) secretKey = str
                        }
                        offset += length
                    }

                    1 -> offset += 8

                    5 -> offset += 4

                    else -> { /* skip */ }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing session response", e)
        }

        if (secretKey.isEmpty()) {
            throw RuntimeException("No secretKey in session response")
        }

        return VtransSession(
            uuid = uuid,
            secretKey = secretKey,
            expires = expires,
            createdAt = System.currentTimeMillis() / 1000
        )
    }

    // =========================================================================
    // Sec-Vtrans headers (matches vot.js getSecYaHeaders)
    // =========================================================================

    /**
     * Generates the 3 security headers required by the API:
     *   Vtrans-Signature: HMAC-SHA256 of the request body
     *   Sec-Vtrans-Sk: secretKey from session
     *   Sec-Vtrans-Token: HMAC(uuid:path:version):uuid:path:version
     */
    private fun buildSecHeaders(
        session: VtransSession,
        requestBody: ByteArray,
        path: String
    ): Map<String, String> {
        val bodySignature = hmacSign(requestBody)

        // Build token: uuid:path:componentVersion
        val token = "${session.uuid}:$path:$COMPONENT_VERSION"
        val tokenBytes = token.toByteArray(Charsets.UTF_8)
        val tokenSign = hmacSign(tokenBytes)

        return mapOf(
            "Vtrans-Signature" to bodySignature,
            "Sec-Vtrans-Sk" to session.secretKey,
            "Sec-Vtrans-Token" to "$tokenSign:$token"
        )
    }

    private fun requestFailAudio(session: VtransSession, videoUrl: String) {
        val path = "/video-translation/fail-audio-js"
        val body = """{"video_url":"$videoUrl"}""".toByteArray(Charsets.UTF_8)
        val secHeaders = buildSecHeaders(session, body, path)

        val url = URL("$API_HOST$path")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (SESSION_ID.isNotEmpty()) {
                conn.setRequestProperty("Cookie", "Session_id=$SESSION_ID")
            }
            secHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }

            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            Log.d(TAG, "fail-audio-js: HTTP $code")
        } catch (e: Exception) {
            Log.e(TAG, "fail-audio-js error", e)
        } finally {
            conn.disconnect()
        }
    }

    private fun requestAudioEmpty(session: VtransSession, videoUrl: String, translationId: String) {
        val path = "/video-translation/audio"
        val audioInfoBaos = ByteArrayOutputStream()
        writeString(audioInfoBaos, 1, "web_api_get_all_generating_urls_data_from_iframe")
        val audioInfoBytes = audioInfoBaos.toByteArray()

        val reqBaos = ByteArrayOutputStream()
        writeString(reqBaos, 1, translationId)
        writeString(reqBaos, 2, videoUrl)
        writeVarint(reqBaos, (6 shl 3) or 2)
        writeVarint(reqBaos, audioInfoBytes.size)
        reqBaos.write(audioInfoBytes)

        val body = reqBaos.toByteArray()
        val secHeaders = buildSecHeaders(session, body, path)

        val urlObj = URL("$API_HOST$path")
        val conn = urlObj.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Content-Type", "application/x-protobuf")
            conn.setRequestProperty("Accept", "application/x-protobuf")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (SESSION_ID.isNotEmpty()) {
                conn.setRequestProperty("Cookie", "Session_id=$SESSION_ID")
            }
            secHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }

            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            Log.d(TAG, "audio-empty: HTTP $code")
        } catch (e: Exception) {
            Log.e(TAG, "audio-empty error", e)
        } finally {
            conn.disconnect()
        }
    }

    // =========================================================================
    // Translation request
    // =========================================================================

    private fun makeTranslationRequest(
        videoUrl: String,
        duration: Double,
        videoTitle: String,
        fromLang: String,
        toLang: String,
        firstRequest: Boolean
    ): TranslationResult {
        // Step 1: Get or create session
        val session = try {
            getOrCreateSession()
        } catch (e: Exception) {
            Log.e(TAG, "Session creation failed", e)
            return TranslationResult.Error("Session error: ${e.message}")
        }

        // Step 2: Build protobuf request
        val requestBody = buildProtobufRequest(
            videoUrl,
            duration,
            videoTitle,
            fromLang,
            toLang,
            firstRequest
        )

        // Step 3: Build sec headers
        val secHeaders = buildSecHeaders(session, requestBody, TRANSLATE_PATH)

        // Step 4: Send request
        val url = URL("$API_HOST$TRANSLATE_PATH")
        val conn = url.openConnection() as HttpURLConnection

        try {
            conn.apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Content-Type", "application/x-protobuf")
                setRequestProperty("Accept", "application/x-protobuf")
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept-Language", "en")
                setRequestProperty("Pragma", "no-cache")
                setRequestProperty("Cache-Control", "no-cache")
                if (SESSION_ID.isNotEmpty()) {
                    setRequestProperty("Cookie", "Session_id=$SESSION_ID")
                }

                // Security headers from session
                secHeaders.forEach { (key, value) ->
                    setRequestProperty(key, value)
                }
            }

            conn.outputStream.use { it.write(requestBody) }

            val responseCode = conn.responseCode
            Log.d(TAG, "Translation response: HTTP $responseCode")

            if (responseCode != 200) {
                val errBody = try {
                    conn.errorStream?.use { String(it.readBytes()) } ?: "no body"
                } catch (_: Exception) {
                    "unreadable"
                }
                Log.e(TAG, "HTTP $responseCode, error: $errBody")

                // Invalidate session on 401/403
                if (responseCode in listOf(401, 403)) {
                    currentSession = null
                }

                return TranslationResult.Error("HTTP error: $responseCode")
            }

            val responseBytes = conn.inputStream.use { it.readBytes() }
            return parseProtobufResponse(responseBytes)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Builds protobuf matching vot.js VideoTranslationRequest schema.
     * Field 18 (useLivelyVoice) set to 0 since we don't have OAuth token.
     */
    private fun buildProtobufRequest(
        url: String,
        duration: Double,
        title: String,
        fromLang: String,
        toLang: String,
        firstRequest: Boolean
    ): ByteArray {
        val baos = ByteArrayOutputStream()

        // 3: url (string)
        writeString(baos, 3, url)
        // 5: firstRequest (bool as varint), default false so we omit if false
        if (firstRequest) {
            writeVarintField(baos, 5, 1)
        }
        // 6: duration (double / fixed64)
        writeDouble(baos, 6, duration)
        // 7: unknown0 = 1
        writeVarintField(baos, 7, 1)
        // 8: language (string) — source language
        writeString(baos, 8, fromLang)
        // 14: responseLanguage (string) — target language
        writeString(baos, 14, toLang)
        // 15: unknown2 = 1
        writeVarintField(baos, 15, 1)
        // 16: unknown3 = 2
        writeVarintField(baos, 16, 2)
        // 18: useLivelyVoice (bool)
        if (SESSION_ID.isNotEmpty()) {
            writeVarintField(baos, 18, 1)
        }
        // 19: videoTitle (string)
        writeString(baos, 19, title)

        return baos.toByteArray()
    }

    private fun parseProtobufResponse(data: ByteArray): TranslationResult {
        var translationUrl: String? = null
        var translationId: String? = null
        var translationDuration = 0.0
        var status = -1
        var remainingTime = 0
        var errorMessage: String? = null

        var offset = 0
        try {
            while (offset < data.size) {
                val (tag, newOff1) = readVarint(data, offset)
                offset = newOff1
                val fieldNumber = (tag shr 3).toInt()
                val wireType = (tag and 0x07).toInt()

                when (wireType) {
                    0 -> {
                        val (value, newOff2) = readVarint(data, offset)
                        offset = newOff2
                        when (fieldNumber) {
                            4 -> status = value.toInt()
                            5 -> remainingTime = value.toInt()
                        }
                    }

                    1 -> {
                        if (offset + 8 <= data.size) {
                            val bb = ByteBuffer.wrap(data, offset, 8)
                                .order(ByteOrder.LITTLE_ENDIAN)
                            val d = bb.double
                            offset += 8
                            if (fieldNumber == 2) translationDuration = d
                        }
                    }

                    2 -> {
                        val (len, newOff3) = readVarint(data, offset)
                        offset = newOff3
                        val length = len.toInt()
                        if (offset + length <= data.size) {
                            val str = String(data, offset, length, Charsets.UTF_8)
                            when (fieldNumber) {
                                1 -> translationUrl = str
                                7 -> translationId = str
                                9 -> errorMessage = str
                            }
                        }
                        offset += length
                    }

                    5 -> offset += 4

                    else -> { /* skip unknown wire types */ }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing response Protobuf", e)
        }

        Log.d(
            TAG,
            "Response: status=$status, url=${translationUrl?.take(80)}, " +
                "remaining=$remainingTime, error=$errorMessage"
        )

        // Status codes from vot.js VideoTranslationStatus:
        // 0 = FAILED, 1 = FINISHED, 2 = WAITING,
        // 3 = LONG_WAITING, 5 = PART_CONTENT, 6 = AUDIO_REQUESTED
        return when (status) {
            1, 5 -> {
                val currentUrl = translationUrl
                if (!currentUrl.isNullOrEmpty()) {
                    TranslationResult.Success(currentUrl, translationDuration)
                } else {
                    TranslationResult.InProgress(remainingTime, status, translationId)
                }
            }

            2, 3, 6 -> TranslationResult.InProgress(remainingTime, status, translationId)

            0 -> TranslationResult.Error(
                errorMessage ?: "Translation failed (Status 0)"
            )

            else -> TranslationResult.Error("Unknown status: $status")
        }
    }

    // =========================================================================
    // Protobuf helpers
    // =========================================================================

    private fun writeString(
        out: ByteArrayOutputStream,
        fieldNumber: Int,
        value: String
    ) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeVarint(out, (fieldNumber shl 3) or 2)
        writeVarint(out, bytes.size)
        out.write(bytes)
    }

    private fun writeVarintField(
        out: ByteArrayOutputStream,
        fieldNumber: Int,
        value: Int
    ) {
        writeVarint(out, (fieldNumber shl 3) or 0)
        writeVarint(out, value)
    }

    private fun writeDouble(
        out: ByteArrayOutputStream,
        fieldNumber: Int,
        value: Double
    ) {
        writeVarint(out, (fieldNumber shl 3) or 1)
        val bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        bb.putDouble(value)
        out.write(bb.array())
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v and -0x80 != 0) {
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
    // Crypto helpers
    // =========================================================================

    private fun hmacSign(data: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(
            HMAC_KEY.toByteArray(Charsets.UTF_8),
            "HmacSHA256"
        )
        mac.init(keySpec)
        val hash = mac.doFinal(data)
        return hash.joinToString("") { "%02x".format(it) }
    }

    /** Generate UUID matching vot.js getUUID() — 32 hex chars, uppercase */
    private fun generateUUID(): String {
        val hexDigits = "0123456789ABCDEF"
        val sb = StringBuilder(32)
        val random = java.security.SecureRandom()
        for (i in 0 until 32) {
            sb.append(hexDigits[random.nextInt(16)])
        }
        return sb.toString()
    }

    private fun shortenYouTubeUrl(url: String): String {
        return try {
            val videoId = if (url.contains("youtu.be/")) {
                url.substringAfter("youtu.be/")
                    .substringBefore("?")
                    .substringBefore("&")
            } else if (url.contains("v=")) {
                url.substringAfter("v=").substringBefore("&")
            } else {
                null
            }

            if (videoId != null) "https://youtu.be/$videoId" else url
        } catch (_: Exception) {
            url
        }
    }
}
