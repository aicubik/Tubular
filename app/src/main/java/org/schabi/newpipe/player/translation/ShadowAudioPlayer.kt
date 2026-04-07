package org.schabi.newpipe.player.translation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import kotlin.math.abs

/**
 * A "shadow" audio player that plays the translated audio track
 * synchronized with the main video player.
 *
 * Design architecture (based on reliable patterns for dual-player sync):
 * Uses a polling loop (every 500ms) rather than spammy ExoPlayer listeners.
 * 1. Matches playing state (play/pause).
 * 2. Matches speed (playback parameters).
 * 3. Keeps timestamp synchronized (seeks shadow if drift > 400ms).
 */
class ShadowAudioPlayer(
    private val context: Context
) {

    companion object {
        private const val TAG = "ShadowAudioPlayer"
        private const val SYNC_INTERVAL_MS = 500L
        private const val MAX_DRIFT_MS = 400L // If drift exceeds this, seek to resync
    }

    private var shadowPlayer: ExoPlayer? = null
    private var mainPlayer: ExoPlayer? = null
    private var audioDuckingController: AudioDuckingController? = null

    private var isTranslationActive = false
    private var translationDurationMs: Long = 0

    private val syncHandler = Handler(Looper.getMainLooper())
    private val syncRunnable = object : Runnable {
        override fun run() {
            syncPlayers()
            if (isTranslationActive) {
                syncHandler.postDelayed(this, SYNC_INTERVAL_MS)
            }
        }
    }

    private val shadowPlayerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Shadow player error: ${error.message}", error)
            stopTranslation()
        }
    }

    fun init(mainExoPlayer: ExoPlayer, duckingController: AudioDuckingController) {
        mainPlayer = mainExoPlayer
        audioDuckingController = duckingController

        val audioAttributes = com.google.android.exoplayer2.audio.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.CONTENT_TYPE_SPEECH)
            .build()

        shadowPlayer = ExoPlayer.Builder(context)
            .build()
            .apply {
                setWakeMode(C.WAKE_MODE_NETWORK)
                // handleAudioFocus = false: let both players coexist, prevent focus stealing
                setAudioAttributes(audioAttributes, false)
                addListener(shadowPlayerListener)
            }

        Log.d(TAG, "Shadow player initialized")
    }

    fun loadTranslation(audioUrl: String, durationSeconds: Double) {
        val player = shadowPlayer ?: return
        val main = mainPlayer ?: return

        translationDurationMs = (durationSeconds * 1000).toLong()

        Log.d(TAG, "Loading translation: url=${audioUrl.take(60)}, dur=${durationSeconds}s")

        val mediaItem = MediaItem.fromUri(audioUrl)
        player.setMediaItem(mediaItem)
        player.prepare()

        // Sync and enable ducking immediately
        isTranslationActive = true
        audioDuckingController?.enableDucking()

        // Start the sync loop which will handle seeking and playing
        startSyncLoop()
        Log.d(TAG, "Translation active and syncing started!")
    }

    private fun startSyncLoop() {
        syncHandler.removeCallbacks(syncRunnable)
        syncHandler.post(syncRunnable)
    }

    private fun stopSyncLoop() {
        syncHandler.removeCallbacks(syncRunnable)
    }

    /**
     * Called every 500ms to align the shadow player perfectly with the main player.
     * This avoids event loops, stuttering on micro-buffers, and disconnects.
     */
    private fun syncPlayers() {
        val sp = shadowPlayer ?: return
        val main = mainPlayer ?: return

        if (!isTranslationActive) return

        // 1. Sync Playback Parameters (Speed)
        if (sp.playbackParameters != main.playbackParameters) {
            sp.playbackParameters = main.playbackParameters
        }

        // 2. State & Position check
        val mainIsPlaying = main.isPlaying
        val mainPos = main.currentPosition
        val spPos = sp.currentPosition

        // If main player finished or we reached the end of the translation audio bounds
        val overDuration = translationDurationMs > 0 && mainPos >= translationDurationMs
        if (main.playbackState == Player.STATE_ENDED || overDuration) {
            if (sp.isPlaying) {
                sp.pause()
                Log.d(TAG, "Shadow paused: target exceeded duration bounds")
            }
            return
        }

        // 3. Play/Pause state matching
        if (mainIsPlaying != sp.isPlaying) {
            // Main player is playing and shadow isn't (or vice versa)
            if (mainIsPlaying) {
                sp.play()
            } else {
                sp.pause()
            }
        }

        // 4. Position Drift Synchronization
        // Only seek if we are drifting significantly (prevent audio tearing/flushing)
        if (mainIsPlaying) {
            val drift = abs(mainPos - spPos)
            if (drift > MAX_DRIFT_MS) {
                Log.d(TAG, "Resyncing shadow. Drift: ${drift}ms. main=$mainPos, shadow=$spPos")
                sp.seekTo(mainPos)
            }
        } else {
            // Always perfectly match when paused to prepare for playback resume
            if (abs(mainPos - spPos) > 50L) {
                sp.seekTo(mainPos)
            }
        }
    }

    fun stopTranslation() {
        if (!isTranslationActive) return
        isTranslationActive = false

        stopSyncLoop()

        shadowPlayer?.apply {
            stop()
            clearMediaItems()
        }

        audioDuckingController?.disableDucking()
        Log.d(TAG, "Translation stopped")
    }

    fun isActive(): Boolean = isTranslationActive

    fun release() {
        stopTranslation()

        shadowPlayer?.apply {
            removeListener(shadowPlayerListener)
            release()
        }
        shadowPlayer = null
        mainPlayer = null

        Log.d(TAG, "Shadow player released")
    }
}
