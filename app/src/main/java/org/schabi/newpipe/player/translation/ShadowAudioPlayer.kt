package org.schabi.newpipe.player.translation

import android.content.Context
import android.util.Log
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player

/**
 * A "shadow" audio player that plays the translated audio track
 * synchronized with the main video player.
 *
 * It listens to the main player's state changes (play/pause/seek)
 * and mirrors them on the translation audio track.
 */
class ShadowAudioPlayer(
    private val context: Context
) : Player.Listener {

    companion object {
        private const val TAG = "ShadowAudioPlayer"

        // Max acceptable sync drift (ms) before forcing a re-sync
        private const val SYNC_THRESHOLD_MS = 500L

        // How often to check sync (ms)
        private const val SYNC_CHECK_INTERVAL_MS = 2000L
    }

    private var shadowPlayer: ExoPlayer? = null
    private var mainPlayer: ExoPlayer? = null
    private var audioDuckingController: AudioDuckingController? = null

    // Track if translation is active
    private var isTranslationActive = false

    // Translation audio duration (may differ from video duration)
    private var translationDurationMs: Long = 0

    /**
     * Initialize the shadow player and connect it to the main player.
     */
    fun init(mainExoPlayer: ExoPlayer, duckingController: AudioDuckingController) {
        mainPlayer = mainExoPlayer
        audioDuckingController = duckingController

        shadowPlayer = ExoPlayer.Builder(context)
            .build()
            .apply {
                // Audio only — no video rendering needed
                setWakeMode(C.WAKE_MODE_NETWORK)
                addListener(this@ShadowAudioPlayer)
            }

        // Listen to main player events
        mainExoPlayer.addListener(this)

        Log.d(TAG, "Shadow player initialized")
    }

    /**
     * Load and play the translation audio from URL.
     */
    fun loadTranslation(audioUrl: String, durationSeconds: Double) {
        val player = shadowPlayer ?: return
        val main = mainPlayer ?: return

        translationDurationMs = (durationSeconds * 1000).toLong()

        // Create media item for the translation audio
        val mediaItem = MediaItem.fromUri(audioUrl)
        player.setMediaItem(mediaItem)
        player.prepare()

        // Sync with main player's current position
        val mainPosition = main.currentPosition
        if (mainPosition > 0) {
            player.seekTo(mainPosition)
        }

        // Match the play state
        player.playWhenReady = main.isPlaying

        // Duck the main player's volume
        audioDuckingController?.enableDucking()

        isTranslationActive = true
        Log.d(TAG, "Translation loaded: $audioUrl, duration: ${durationSeconds}s")
    }

    /**
     * Stop translation playback and restore main player volume.
     */
    fun stopTranslation() {
        shadowPlayer?.apply {
            stop()
            clearMediaItems()
        }

        audioDuckingController?.disableDucking()
        isTranslationActive = false

        Log.d(TAG, "Translation stopped")
    }

    /**
     * Check if translation is currently playing.
     */
    fun isActive(): Boolean = isTranslationActive

    /**
     * Release all resources.
     */
    fun release() {
        mainPlayer?.removeListener(this)
        stopTranslation()

        shadowPlayer?.apply {
            removeListener(this@ShadowAudioPlayer)
            release()
        }
        shadowPlayer = null
        mainPlayer = null

        Log.d(TAG, "Shadow player released")
    }

    // =========================================================================
    // Main player event listeners — keep shadow player in sync
    // =========================================================================

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (!isTranslationActive) return

        shadowPlayer?.let { sp ->
            if (isPlaying) {
                if (!sp.isPlaying) {
                    sp.play()
                    syncPosition()
                }
            } else {
                if (sp.isPlaying) {
                    sp.pause()
                }
            }
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (!isTranslationActive) return

        when (playbackState) {
            Player.STATE_ENDED -> {
                // Main video ended — stop translation too
                stopTranslation()
            }
            Player.STATE_READY -> {
                // Ensure sync when playback resumes
                syncPosition()
            }
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        if (!isTranslationActive) return

        // User performed a seek — mirror it on the shadow player
        if (reason == Player.DISCONTINUITY_REASON_SEEK
            || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
        ) {
            val newPositionMs = newPosition.positionMs
            shadowPlayer?.seekTo(newPositionMs)
            Log.d(TAG, "Seek synced to ${newPositionMs}ms")
        }
    }

    override fun onPlaybackParametersChanged(
        playbackParameters: com.google.android.exoplayer2.PlaybackParameters
    ) {
        if (!isTranslationActive) return

        // Mirror playback speed changes
        shadowPlayer?.playbackParameters = playbackParameters
        Log.d(TAG, "Playback speed synced: ${playbackParameters.speed}x")
    }

    override fun onPlayerError(error: PlaybackException) {
        Log.e(TAG, "Shadow player error: ${error.message}")
        // Don't crash the main player — just stop translation silently
        stopTranslation()
    }

    // =========================================================================
    // Sync helpers
    // =========================================================================

    /**
     * Force-sync the shadow player position to match the main player.
     */
    private fun syncPosition() {
        val main = mainPlayer ?: return
        val shadow = shadowPlayer ?: return

        if (!isTranslationActive) return

        val mainPos = main.currentPosition
        val shadowPos = shadow.currentPosition
        val drift = kotlin.math.abs(mainPos - shadowPos)

        if (drift > SYNC_THRESHOLD_MS) {
            shadow.seekTo(mainPos)
            Log.d(TAG, "Re-synced: drift was ${drift}ms")
        }
    }
}
