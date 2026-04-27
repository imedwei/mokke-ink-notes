package com.writer.ui.writing

import com.writer.model.AnchorTarget
import com.writer.model.AudioRecording
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.TextBlock
import com.writer.recognition.LineSegmenter
import com.writer.view.ScreenMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Pure-JVM tests for [TextBlockAnchorComputer].
 *
 * Exercises the audio↔stroke time-correlation rule that picks
 *   (target ∈ {MAIN, CUE}, lineIndex)
 * for each TextBlock given strokes in both columns and the recordings list.
 */
class TextBlockAnchorComputerTest {

    companion object {
        private const val DENSITY = 1.875f
        /** Wall-clock ms when the recording started. Chosen large so raw
         *  audio-ms values (0..Nk) don't accidentally fall inside the window. */
        private const val REC_START = 1_000_000L
    }

    private lateinit var computer: TextBlockAnchorComputer
    private lateinit var segmenter: LineSegmenter

    @Before
    fun setUp() {
        ScreenMetrics.init(DENSITY, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
        segmenter = LineSegmenter()
        computer = TextBlockAnchorComputer(segmenter)
    }

    /** One-point stroke at the center of [line] with wall-clock [timestampMs]. */
    private fun stroke(line: Int, timestampMs: Long): InkStroke {
        val y = ScreenMetrics.topMargin + line * ScreenMetrics.lineSpacing + ScreenMetrics.lineSpacing / 2
        return InkStroke(points = listOf(StrokePoint(100f, y, 0.5f, timestampMs)))
    }

    private fun recording(file: String = "rec.opus", start: Long = REC_START) =
        AudioRecording(audioFile = file, startTimeMs = start, durationMs = 60_000L)

    private fun block(
        file: String = "rec.opus",
        audioStartMs: Long = 0L,
        audioEndMs: Long = 1_000L,
    ) = TextBlock(
        startLineIndex = 0,
        heightInLines = 1,
        audioFile = file,
        audioStartMs = audioStartMs,
        audioEndMs = audioEndMs,
    )

    // ── Presence / target selection ─────────────────────────────────────────

    @Test
    fun `no strokes in window returns null`() {
        val strokes = listOf(stroke(3, REC_START - 10))  // before window
        val result = computer.computeAnchor(block(), strokes, emptyList(), listOf(recording()))
        assertNull(result)
    }

    @Test
    fun `only main strokes in window targets main`() {
        val strokes = listOf(stroke(3, REC_START + 500))
        val result = computer.computeAnchor(block(), strokes, emptyList(), listOf(recording()))
        assertEquals(AnchorTarget.MAIN, result?.target)
        assertEquals(3, result?.lineIndex)
    }

    @Test
    fun `only cue strokes in window targets cue`() {
        val strokes = listOf(stroke(3, REC_START + 500))
        val result = computer.computeAnchor(block(), emptyList(), strokes, listOf(recording()))
        assertEquals(AnchorTarget.CUE, result?.target)
        assertEquals(3, result?.lineIndex)
    }

    @Test
    fun `mixed strokes with main majority targets main`() {
        val main = listOf(stroke(2, REC_START + 100), stroke(2, REC_START + 200), stroke(4, REC_START + 300))
        val cue = listOf(stroke(1, REC_START + 400))
        val result = computer.computeAnchor(block(), main, cue, listOf(recording()))
        assertEquals(AnchorTarget.MAIN, result?.target)
    }

    @Test
    fun `mixed strokes with cue majority targets cue`() {
        val main = listOf(stroke(2, REC_START + 100))
        val cue = listOf(stroke(1, REC_START + 200), stroke(1, REC_START + 300), stroke(4, REC_START + 400))
        val result = computer.computeAnchor(block(), main, cue, listOf(recording()))
        assertEquals(AnchorTarget.CUE, result?.target)
    }

    @Test
    fun `tie between main and cue prefers main`() {
        val main = listOf(stroke(2, REC_START + 100), stroke(3, REC_START + 200))
        val cue = listOf(stroke(5, REC_START + 300), stroke(6, REC_START + 400))
        val result = computer.computeAnchor(block(), main, cue, listOf(recording()))
        assertEquals(AnchorTarget.MAIN, result?.target)
    }

    // ── Median line index ───────────────────────────────────────────────────

    @Test
    fun `median line index with odd stroke count`() {
        // Lines sorted: [1, 3, 7] — size/2 = 1 → lines[1] = 3
        val main = listOf(stroke(1, REC_START + 100), stroke(7, REC_START + 200), stroke(3, REC_START + 300))
        val result = computer.computeAnchor(block(), main, emptyList(), listOf(recording()))
        assertEquals(3, result?.lineIndex)
    }

    @Test
    fun `median line index with even stroke count picks upper middle`() {
        // Lines sorted: [1, 3, 5, 7] — size/2 = 2 → lines[2] = 5
        val main = listOf(
            stroke(1, REC_START + 1), stroke(3, REC_START + 2),
            stroke(5, REC_START + 3), stroke(7, REC_START + 4),
        )
        val result = computer.computeAnchor(block(), main, emptyList(), listOf(recording()))
        assertEquals(5, result?.lineIndex)
    }

    @Test
    fun `outlier stroke does not shift median`() {
        // Lines sorted: [2,2,2,2,99] — median = lines[2] = 2
        val main = listOf(
            stroke(2, REC_START + 1), stroke(2, REC_START + 2),
            stroke(2, REC_START + 3), stroke(2, REC_START + 4),
            stroke(99, REC_START + 5),
        )
        val result = computer.computeAnchor(block(), main, emptyList(), listOf(recording()))
        assertEquals(2, result?.lineIndex)
    }

    @Test
    fun `one stroke per line picks middle line as median`() {
        val main = (0..4).map { stroke(it, REC_START + 100L + it) }
        val result = computer.computeAnchor(block(), main, emptyList(), listOf(recording()))
        assertEquals(2, result?.lineIndex)
    }

    @Test
    fun `many strokes on single line pick that line as median`() {
        val main = (1..10).map { stroke(7, REC_START + it * 10L) }
        val result = computer.computeAnchor(block(), main, emptyList(), listOf(recording()))
        assertEquals(7, result?.lineIndex)
    }

    // ── Window boundary inclusivity ─────────────────────────────────────────

    @Test
    fun `stroke at audio start ms is included`() {
        val b = block(audioStartMs = 500, audioEndMs = 1500)
        val main = listOf(stroke(3, REC_START + 500))
        val result = computer.computeAnchor(b, main, emptyList(), listOf(recording()))
        assertEquals(3, result?.lineIndex)
    }

    @Test
    fun `stroke at audio end ms is included`() {
        val b = block(audioStartMs = 500, audioEndMs = 1500)
        val main = listOf(stroke(3, REC_START + 1500))
        val result = computer.computeAnchor(b, main, emptyList(), listOf(recording()))
        assertEquals(3, result?.lineIndex)
    }

    @Test
    fun `stroke one ms before audio start is excluded`() {
        val b = block(audioStartMs = 500, audioEndMs = 1500)
        val main = listOf(stroke(3, REC_START + 499))
        val result = computer.computeAnchor(b, main, emptyList(), listOf(recording()))
        assertNull(result)
    }

    // ── Wall-clock conversion ───────────────────────────────────────────────

    @Test
    fun `wall clock conversion adds recording start time ms`() {
        // Without conversion, a stroke at raw ms=500 would match block's audio window [0..1000].
        // With conversion the true window is [REC_START..REC_START+1000]; raw 500 is excluded.
        val main = listOf(stroke(3, 500L))
        val result = computer.computeAnchor(block(), main, emptyList(), listOf(recording()))
        assertNull(result)
    }

    // ── Recording lookup ────────────────────────────────────────────────────

    @Test
    fun `missing recording entry returns null`() {
        val b = block(file = "nonexistent.opus")
        val main = listOf(stroke(3, REC_START + 100))
        val result = computer.computeAnchor(b, main, emptyList(), listOf(recording(file = "other.opus")))
        assertNull(result)
    }

    @Test
    fun `multiple recordings picked by audio file`() {
        val rec1 = AudioRecording("a.opus", startTimeMs = 1_000_000L, durationMs = 60_000L)
        val rec2 = AudioRecording("b.opus", startTimeMs = 5_000_000L, durationMs = 60_000L)
        val b = TextBlock(
            startLineIndex = 0, heightInLines = 1,
            audioFile = "b.opus", audioStartMs = 0, audioEndMs = 1000,
        )
        val main = listOf(stroke(4, 5_000_500L))  // inside rec2's window
        val result = computer.computeAnchor(b, main, emptyList(), listOf(rec1, rec2))
        assertEquals(4, result?.lineIndex)
    }
}
