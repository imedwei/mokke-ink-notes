package com.writer.audio

/**
 * Monitors audio quality from RMS dB readings reported by SpeechRecognizer.
 *
 * Tracks noise floor (silence/background) vs speech peaks to estimate
 * signal-to-noise ratio and warn about poor recording conditions.
 *
 * RMS values from Android SpeechRecognizer are typically in range -2 to 10 dB
 * (relative, not absolute SPL). Values below 0 indicate silence/noise floor.
 */
class AudioQualityMonitor {

    private val recentRms = ArrayDeque<Float>(WINDOW_SIZE)
    private var noiseFloor = 0f
    private var peakSpeech = -2f
    private var sampleCount = 0
    private var speechFrames = 0
    private var silenceFrames = 0

    /** Called on each RMS reading from SpeechRecognizer. */
    fun onRmsChanged(rmsdB: Float) {
        recentRms.addLast(rmsdB)
        if (recentRms.size > WINDOW_SIZE) recentRms.removeFirst()
        sampleCount++

        if (rmsdB < SPEECH_THRESHOLD) {
            silenceFrames++
            // Update noise floor as running average of quiet frames
            noiseFloor = noiseFloor * 0.95f + rmsdB * 0.05f
        } else {
            speechFrames++
            if (rmsdB > peakSpeech) peakSpeech = rmsdB
        }
    }

    /** Estimated SNR in dB (speech peak minus noise floor). */
    val snr: Float get() = (peakSpeech - noiseFloor).coerceAtLeast(0f)

    /** Estimated noise floor in dB (averaged from quiet frames). */
    val noiseFloorDb: Float get() = noiseFloor

    /** Peak speech level observed in dB. */
    val peakSpeechDb: Float get() = peakSpeech

    /** Current RMS level (smoothed over recent window). */
    val currentRms: Float get() = if (recentRms.isEmpty()) -2f else recentRms.average().toFloat()

    /** Quality assessment based on current measurements. */
    val quality: Quality get() {
        if (sampleCount < MIN_SAMPLES) return Quality.MEASURING
        return when {
            snr >= GOOD_SNR -> Quality.GOOD
            snr >= FAIR_SNR -> Quality.FAIR
            else -> Quality.POOR
        }
    }

    /** Human-readable quality description. */
    val qualityMessage: String get() = when (quality) {
        Quality.MEASURING -> "Measuring audio quality..."
        Quality.GOOD -> "Audio quality: good"
        Quality.FAIR -> "Audio quality: fair — move closer or reduce background noise"
        Quality.POOR -> "Audio quality: poor — too much background noise"
    }

    /** Whether a warning should be shown to the user. */
    val shouldWarn: Boolean get() = quality == Quality.POOR && sampleCount >= MIN_SAMPLES

    fun reset() {
        recentRms.clear()
        noiseFloor = 0f
        peakSpeech = -2f
        sampleCount = 0
        speechFrames = 0
        silenceFrames = 0
    }

    enum class Quality { MEASURING, GOOD, FAIR, POOR }

    companion object {
        private const val WINDOW_SIZE = 30
        private const val MIN_SAMPLES = 50
        private const val SPEECH_THRESHOLD = 2f   // dB above which we consider speech
        private const val GOOD_SNR = 6f           // dB: good separation
        private const val FAIR_SNR = 3f           // dB: marginal separation
    }
}
