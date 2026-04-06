package org.schabi.newpipe.player.translation

import android.animation.ValueAnimator
import android.util.Log
import com.google.android.exoplayer2.ExoPlayer

/**
 * Controls "audio ducking" — smoothly reduces the main player's volume
 * when translation audio is playing, so the user hears primarily
 * the Russian voiceover with the original audio faintly in the background.
 */
class AudioDuckingController {

    companion object {
        private const val TAG = "AudioDucking"

        // Volume level for the original track when translation is active (0.0 - 1.0)
        private const val DUCKED_VOLUME = 0.15f

        // Normal volume level
        private const val NORMAL_VOLUME = 1.0f

        // Duration of the fade animation in milliseconds
        private const val FADE_DURATION_MS = 500L
    }

    private var mainPlayer: ExoPlayer? = null
    private var currentAnimator: ValueAnimator? = null
    private var isDucking = false

    // User-configurable ducking level (default: 15% of original volume)
    private var duckLevel: Float = DUCKED_VOLUME

    /**
     * Attach to the main ExoPlayer instance.
     */
    fun attach(player: ExoPlayer) {
        mainPlayer = player
    }

    /**
     * Set the ducking level (0.0 = fully muted, 1.0 = no ducking).
     */
    fun setDuckLevel(level: Float) {
        duckLevel = level.coerceIn(0.0f, 1.0f)
        // If currently ducking, update immediately
        if (isDucking) {
            mainPlayer?.volume = duckLevel
        }
    }

    /**
     * Enable ducking — smoothly reduce main player volume.
     */
    fun enableDucking() {
        if (isDucking) return
        isDucking = true

        val player = mainPlayer ?: return
        animateVolume(player, player.volume, duckLevel)

        Log.d(TAG, "Ducking enabled (${(duckLevel * 100).toInt()}%)")
    }

    /**
     * Disable ducking — smoothly restore main player volume.
     */
    fun disableDucking() {
        if (!isDucking) return
        isDucking = false

        val player = mainPlayer ?: return
        animateVolume(player, player.volume, NORMAL_VOLUME)

        Log.d(TAG, "Ducking disabled (100%)")
    }

    /**
     * Toggle ducking state.
     */
    fun toggle() {
        if (isDucking) disableDucking() else enableDucking()
    }

    /**
     * Check if ducking is currently active.
     */
    fun isDuckingActive(): Boolean = isDucking

    /**
     * Release resources.
     */
    fun release() {
        currentAnimator?.cancel()
        currentAnimator = null

        // Restore volume before releasing
        mainPlayer?.volume = NORMAL_VOLUME
        mainPlayer = null
        isDucking = false
    }

    // =========================================================================
    // Animation
    // =========================================================================

    /**
     * Smoothly animate volume from one level to another.
     */
    private fun animateVolume(player: ExoPlayer, from: Float, to: Float) {
        currentAnimator?.cancel()

        currentAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = FADE_DURATION_MS
            addUpdateListener { animation ->
                val value = animation.animatedValue as Float
                player.volume = value
            }
            start()
        }
    }
}
