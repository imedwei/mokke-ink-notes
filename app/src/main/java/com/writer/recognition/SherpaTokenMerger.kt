package com.writer.recognition

import com.writer.model.WordInfo

/**
 * Merges SentencePiece tokens from Sherpa-ONNX into word-level [WordInfo] entries.
 *
 * Sherpa's streaming zipformer uses SentencePiece tokenization where `▁` (U+2581)
 * marks a word boundary. Tokens without the prefix are continuations of the
 * previous word (e.g., `["▁RETURN", "ED"]` → "RETURNED").
 */
object SherpaTokenMerger {

    private const val SP_BOUNDARY = '\u2581' // SentencePiece word boundary
    private const val SPACE = ' '

    /**
     * Latency compensation for streaming transducer timestamps.
     *
     * Streaming RNN-T/Zipformer models emit tokens *after* seeing enough
     * future context, so reported timestamps are systematically later than
     * actual speech onset. The delay depends on the model's chunk size:
     *
     * - chunk-16 (our model) = 16 subsampled frames × 40ms/frame × 0.5 = ~320ms
     *
     * We use half the chunk duration as the compensation because the token
     * is emitted somewhere within the chunk, on average halfway through.
     * This value should be updated if the model changes.
     *
     * References:
     * - FastEmit (Google, 2020): https://arxiv.org/abs/2010.11148
     * - Self-Alignment (Kim & Lu, 2021): https://arxiv.org/abs/2105.05005
     * - Microsoft alignment pre-training: ~400ms delay for vanilla RNN-T
     */
    private const val CHUNK_FRAMES = 16       // from model name: chunk-16-left-128
    private const val FRAME_DURATION_MS = 40L  // 10ms frame shift × 4x conv subsampling
    private const val LATENCY_COMPENSATION_MS = CHUNK_FRAMES * FRAME_DURATION_MS / 2 // 320ms

    /**
     * @param tokens  SentencePiece tokens from [OnlineRecognizerResult.tokens]
     * @param timestamps  per-token timestamps in seconds from [OnlineRecognizerResult.timestamps]
     * @param offsetSec  cumulative audio offset in seconds (for multi-segment recordings
     *                   where Sherpa resets timestamps after each endpoint)
     * @return merged word-level entries with timestamps in milliseconds
     */
    fun mergeTokens(tokens: Array<String>, timestamps: FloatArray, offsetSec: Float = 0f): List<WordInfo> {
        val count = minOf(tokens.size, timestamps.size)
        if (count == 0) return emptyList()

        val words = mutableListOf<WordInfo>()
        var currentText = StringBuilder()
        var wordStartMs = 0L
        var wordLastMs = 0L

        for (i in 0 until count) {
            val token = tokens[i]
            if (token.isBlank()) continue

            // Word boundary: SentencePiece ▁ or leading space
            val isNewWord = token.startsWith(SP_BOUNDARY) || token.startsWith(SPACE)
            val stripped = if (isNewWord) token.trimStart(SP_BOUNDARY, SPACE) else token
            if (stripped.isEmpty()) continue

            if (isNewWord && currentText.isNotEmpty()) {
                // Emit previous word (lowercase — model outputs ALL CAPS)
                words.add(WordInfo(
                    text = currentText.toString().lowercase(),
                    confidence = 1.0f,
                    startMs = wordStartMs,
                    endMs = wordLastMs
                ))
                currentText.clear()
            }

            if (currentText.isEmpty()) {
                wordStartMs = (((timestamps[i] + offsetSec) * 1000).toLong() - LATENCY_COMPENSATION_MS).coerceAtLeast(0)
            }
            currentText.append(stripped)
            wordLastMs = (((timestamps[i] + offsetSec) * 1000).toLong() - LATENCY_COMPENSATION_MS).coerceAtLeast(0)
        }

        // Emit last word
        if (currentText.isNotEmpty()) {
            words.add(WordInfo(
                text = currentText.toString().lowercase(),
                confidence = 1.0f,
                startMs = wordStartMs,
                endMs = wordLastMs
            ))
        }

        // Fix endMs: use next word's startMs as a better estimate for word end
        for (i in 0 until words.size - 1) {
            words[i] = words[i].copy(endMs = words[i + 1].startMs)
        }

        return words
    }
}
