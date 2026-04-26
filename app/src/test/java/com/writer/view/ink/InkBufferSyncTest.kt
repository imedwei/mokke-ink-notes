package com.writer.view.ink

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.widget.FrameLayout
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.maxX
import com.writer.model.maxY
import com.writer.model.minX
import com.writer.model.minY
import com.writer.view.HandwritingCanvasView
import com.writer.view.ScreenMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Corpus of tests verifying that the ION buffer (represented here by the
 * latest bitmap passed to [InkController.syncOverlay]) stays in sync with the
 * host's canonical contentBitmap across the three mutation families that have
 * historically produced "ION is dirty when writing into a clean region" bugs:
 *
 *  1. erase-then-write: strokes removed, then new strokes written into the
 *     vacated region. Asserts the ION is cleared before the new strokes land.
 *  2. scroll-after-erase: erase, scroll, write. The scroll path re-syncs the
 *     overlay; failing to do so leaves stale ink at the old stroke coordinates.
 *  3. commit ordering: every commitMutationImmediate path produces a force-
 *     refresh sync so the EPD catches up — without it the daemon's live overlay
 *     still shows the pre-mutation ink on top of the fresh SurfaceView.
 *
 * These tests use [FakeInkController] rather than [BigmeInkController]. The
 * Bigme daemon is only reachable via reflection on xrz firmware; the fake
 * behaves as "active, consumes input, records every syncOverlay" which is the
 * only shape the host View exercises.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InkBufferSyncTest {

    private lateinit var view: HandwritingCanvasView
    private lateinit var fake: FakeInkController

    // Viewport sized so the six test strokes (each ~20px high) fit on screen
    // without scroll, yet are far enough apart that erase bboxes don't overlap
    // neighboring strokes.
    private val viewW = 400
    private val viewH = 600

    @Before
    fun setUp() {
        // Initialize ScreenMetrics so LINE_SPACING / TOP_MARGIN / dp() work.
        ScreenMetrics.init(1f, smallestWidthDp = 400, widthPixels = viewW, heightPixels = viewH)
        // Attach via an Activity so the view has a non-null Handler and window
        // context — several code paths (beginStroke idle-runnable, choreographer
        // post, holder.lockCanvas) require an attached view.
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val container = FrameLayout(activity)
        activity.setContentView(container)
        view = HandwritingCanvasView(activity)
        container.addView(view, FrameLayout.LayoutParams(viewW, viewH))
        fake = FakeInkController()
        view.setInkControllerForTest(fake)
        view.prepareForTest(viewW, viewH)
    }

    /** Build a short diagonal line stroke at the given center. Low point count,
     *  non-geometric, non-scratch — avoids snap detection and scratch-out. */
    private fun stroke(cx: Float, cy: Float): List<StrokePoint> {
        val t0 = 1000L
        return listOf(
            StrokePoint(cx - 10f, cy - 5f, 0.5f, t0),
            StrokePoint(cx - 5f, cy - 3f, 0.5f, t0 + 10),
            StrokePoint(cx + 5f, cy + 3f, 0.5f, t0 + 20),
            StrokePoint(cx + 10f, cy + 5f, 0.5f, t0 + 30),
        )
    }

    /** Returns the (x,y,color) of the first stroke-ink pixel inside [rect], or
     *  null if the region is clean of black ink. Ruled lines (gray #AAAAAA)
     *  are ignored so a region can be "clean" even when a ruled line crosses.
     *  Fully-transparent pixels (0x00000000, which some Bitmap.Config.ARGB_8888
     *  regions are initialised to) also don't count as ink. */
    private fun findInk(bmp: Bitmap, rect: Rect, step: Int = 1): Triple<Int, Int, Int>? {
        val left = rect.left.coerceAtLeast(0)
        val top = rect.top.coerceAtLeast(0)
        val right = rect.right.coerceAtMost(bmp.width)
        val bottom = rect.bottom.coerceAtMost(bmp.height)
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val p = bmp.getPixel(x, y)
                if (Color.alpha(p) >= 128 &&
                    Color.red(p) < 80 && Color.green(p) < 80 && Color.blue(p) < 80) {
                    return Triple(x, y, p)
                }
                x += step
            }
            y += step
        }
        return null
    }

    private fun hasInk(bmp: Bitmap, rect: Rect, step: Int = 1): Boolean =
        findInk(bmp, rect, step) != null

    /** Diagnostic: find the darkest opaque pixel in [rect] — used to pinpoint
     *  whether a stroke rendered at all, and what its actual pixel color is. */
    private fun darkestPixel(bmp: Bitmap, rect: Rect): String {
        val left = rect.left.coerceAtLeast(0)
        val top = rect.top.coerceAtLeast(0)
        val right = rect.right.coerceAtMost(bmp.width)
        val bottom = rect.bottom.coerceAtMost(bmp.height)
        var bestSum = Int.MAX_VALUE
        var bestColor = 0
        var bestX = -1
        var bestY = -1
        for (y in top until bottom) {
            for (x in left until right) {
                val p = bmp.getPixel(x, y)
                if (Color.alpha(p) < 32) continue
                val sum = Color.red(p) + Color.green(p) + Color.blue(p)
                if (sum < bestSum) {
                    bestSum = sum
                    bestColor = p
                    bestX = x
                    bestY = y
                }
            }
        }
        return if (bestX < 0) "no opaque pixels"
        else "($bestX, $bestY) = 0x${Integer.toHexString(bestColor).padStart(8, '0')}"
    }

    private fun inkAssertMsg(label: String, bmp: Bitmap, rect: Rect): String {
        val found = findInk(bmp, rect)
        return if (found == null) "$label — region $rect is clean"
        else "$label — found ink at (${found.first}, ${found.second}) color=0x${Integer.toHexString(found.third)} inside $rect"
    }

    /** True if the two bitmaps have identical pixels inside [rect]. */
    private fun pixelsEqual(a: Bitmap, b: Bitmap, rect: Rect): Boolean {
        val left = rect.left.coerceAtLeast(0)
        val top = rect.top.coerceAtLeast(0)
        val right = rect.right.coerceAtMost(minOf(a.width, b.width))
        val bottom = rect.bottom.coerceAtMost(minOf(a.height, b.height))
        for (y in top until bottom) {
            for (x in left until right) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) return false
            }
        }
        return true
    }

    private fun fullRect() = Rect(0, 0, viewW, viewH)

    // ── 0. Sanity: can Robolectric render Paint-based drawing at all? ─────────

    @Test
    fun canvasSanity_drawPathProducesBlackPixels() {
        val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(android.graphics.Color.WHITE)
        val path = android.graphics.Path().apply {
            moveTo(50f, 50f)
            lineTo(150f, 150f)
        }
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            strokeWidth = 3f
            style = android.graphics.Paint.Style.STROKE
            isAntiAlias = false
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        c.drawPath(path, paint)
        // The diagonal line should produce black pixels along its length.
        assertTrue(
            "drawPath should produce black pixels (darkest in (90,90)-(110,110): ${darkestPixel(bmp, Rect(90, 90, 110, 110))})",
            hasInk(bmp, Rect(90, 90, 110, 110)),
        )
    }

    // ── 1. Baseline: seed sync happens on prepareForTest ──────────────────────

    @Test
    fun prepareForTest_seedsIonBufferFromContentBitmap() {
        assertTrue("prepareForTest should seed at least one sync call", fake.calls.isNotEmpty())
        val ion = fake.ionBuffer()
        assertNotNull(ion)
        val host = view.contentBitmapForTest()!!
        assertTrue(
            "seeded ION should match host bitmap pixel-for-pixel",
            pixelsEqual(ion!!, host, fullRect()),
        )
    }

    // ── 2. Erase path: ION buffer clean in erased region ──────────────────────

    @Test
    fun eraseStroke_ionBufferClearedInErasedRegion() {
        // Two strokes on different lines so their bboxes don't overlap.
        view.injectStrokeForTest(stroke(100f, 150f))
        view.injectStrokeForTest(stroke(100f, 300f))
        view.flushFrameCallbacksForTest()

        val strokeA = view.getStrokes().first { it.minY < 200f }
        val strokeB = view.getStrokes().first { it.minY > 200f }

        // Sanity: both strokes are on the host bitmap right now.
        val host = view.contentBitmapForTest()!!
        val aBboxCheck = Rect(86, 141, 114, 159)
        val bBboxCheck = Rect(86, 291, 114, 309)
        assertTrue(
            "Pre-erase host bitmap should have stroke A (darkest in $aBboxCheck: ${darkestPixel(host, aBboxCheck)})",
            hasInk(host, aBboxCheck),
        )
        assertTrue(
            "Pre-erase host bitmap should have stroke B (darkest in $bBboxCheck: ${darkestPixel(host, bBboxCheck)})",
            hasInk(host, bBboxCheck),
        )

        // Snapshot A's bbox before we remove it — we'll assert the ION is
        // cleared here afterwards.
        val aBbox = Rect(
            (strokeA.minX - 4f).toInt(),
            (strokeA.minY - 4f).toInt(),
            (strokeA.maxX + 4f).toInt(),
            (strokeA.maxY + 4f).toInt(),
        )
        val bBbox = Rect(
            (strokeB.minX - 4f).toInt(),
            (strokeB.minY - 4f).toInt(),
            (strokeB.maxX + 4f).toInt(),
            (strokeB.maxY + 4f).toInt(),
        )

        // Sanity: ION has ink in A's region before erase. The daemon would be
        // painting A live; here we force-sync from the host bitmap (which has
        // both strokes via appendLastStrokeToBitmap) to stand in for that.
        view.flushFrameCallbacksForTest()
        val markBeforeErase = fake.mark()

        view.removeStrokes(setOf(strokeA.strokeId))
        view.flushFrameCallbacksForTest()

        // removeStrokes → commitMutationImmediate → doCommitMutation →
        // rebuildContentBitmapRegion (clears the region, redraws survivors) →
        // scheduleOverlayRefreshOrDefer → refreshOverlay(force=false) then
        // forcedRefreshCallback → refreshOverlay(force=true).
        assertTrue(
            "erase must produce at least one syncOverlay call",
            fake.calls.size > markBeforeErase,
        )

        val ion = fake.ionBuffer()!!
        assertTrue(
            inkAssertMsg("ION must have no residual ink in erased region", ion, aBbox),
            !hasInk(ion, aBbox),
        )
        assertTrue(
            "ION must still have ink for the surviving stroke (darkest pixel in $bBbox: ${darkestPixel(ion, bBbox)})",
            hasInk(ion, bBbox),
        )
        assertTrue(
            "ION must match host bitmap after erase",
            pixelsEqual(ion, view.contentBitmapForTest()!!, fullRect()),
        )
        assertTrue(
            "erase path must schedule a force=true refresh so the EPD clears residue",
            fake.anySyncSince(markBeforeErase) { it.force },
        )
    }

    // ── 3. Erase → write: new stroke lands on a clean ION background ──────────

    @Test
    fun writeIntoErasedRegion_ionHasNoPriorResidue() {
        // Write a stroke at P, erase it, then write a new stroke at the same P.
        view.injectStrokeForTest(stroke(200f, 250f))
        view.flushFrameCallbacksForTest()

        val oldStroke = view.getStrokes().single()
        view.removeStrokes(setOf(oldStroke.strokeId))
        view.flushFrameCallbacksForTest()

        // After erase, ION must be clean where the stroke used to be. This is
        // the pre-condition for the daemon to paint the NEXT stroke into a
        // clean region — if this fails, the user sees pre-erase residue
        // bleeding through the new strokes.
        val erasedBbox = Rect(
            (oldStroke.minX - 4f).toInt(),
            (oldStroke.minY - 4f).toInt(),
            (oldStroke.maxX + 4f).toInt(),
            (oldStroke.maxY + 4f).toInt(),
        )
        assertTrue(
            inkAssertMsg("ION in erased region must be pristine before any new stroke", fake.ionBuffer()!!, erasedBbox),
            !hasInk(fake.ionBuffer()!!, erasedBbox),
        )

        // Now write a new stroke in the same spot. In production the daemon
        // paints the live stroke directly into ION; here the host's
        // appendLastStrokeToBitmap updates contentBitmap only. The next
        // mutation's syncOverlay is what ultimately reconciles. We trigger
        // that by erasing the new stroke — a second commit boundary.
        view.injectStrokeForTest(stroke(200f, 250f))
        view.flushFrameCallbacksForTest()

        val newStroke = view.getStrokes().single()
        assertTrue(
            "new stroke should be at the same position as the erased one",
            newStroke.minX < 200f && newStroke.maxX > 200f,
        )

        val markBeforeSecondErase = fake.mark()
        view.removeStrokes(setOf(newStroke.strokeId))
        view.flushFrameCallbacksForTest()

        assertTrue(
            "second erase must sync overlay",
            fake.calls.size > markBeforeSecondErase,
        )
        assertTrue(
            "ION in erased region is clean after second commit — no mixing of old + new ink",
            !hasInk(fake.ionBuffer()!!, erasedBbox),
        )
        assertTrue(
            "ION must match host bitmap after erase+write+erase sequence",
            pixelsEqual(fake.ionBuffer()!!, view.contentBitmapForTest()!!, fullRect()),
        )
    }

    // ── 4. Scroll after erase: ION reflects post-scroll positions ─────────────

    @Test
    fun scrollAfterErase_ionReflectsScrolledContent() {
        view.injectStrokeForTest(stroke(100f, 400f))
        view.injectStrokeForTest(stroke(250f, 400f))
        view.flushFrameCallbacksForTest()

        val keepStroke = view.getStrokes().first { it.minX < 200f }
        val eraseStroke = view.getStrokes().first { it.minX > 200f }

        view.removeStrokes(setOf(eraseStroke.strokeId))
        view.flushFrameCallbacksForTest()

        val eraseBboxScreen = Rect(
            (eraseStroke.minX - 4f).toInt(),
            (eraseStroke.minY - 4f).toInt(),
            (eraseStroke.maxX + 4f).toInt(),
            (eraseStroke.maxY + 4f).toInt(),
        )
        assertTrue(
            inkAssertMsg("pre-scroll ION has no erased-stroke residue", fake.ionBuffer()!!, eraseBboxScreen),
            !hasInk(fake.ionBuffer()!!, eraseBboxScreen),
        )

        // Scroll down by 150px: strokes at docY=400 now render at screenY=250.
        val scrollY = 150f
        view.scrollToForTest(scrollY)
        view.flushFrameCallbacksForTest()

        val ion = fake.ionBuffer()!!
        val host = view.contentBitmapForTest()!!

        assertTrue(
            "ION must match host bitmap after scroll",
            pixelsEqual(ion, host, fullRect()),
        )

        // Old screen position (pre-scroll Y of keepStroke) must be clean — no
        // residual ink from before the scroll.
        val oldScreenBbox = Rect(
            (keepStroke.minX - 4f).toInt(),
            (keepStroke.minY - 4f).toInt(),
            (keepStroke.maxX + 4f).toInt(),
            (keepStroke.maxY + 4f).toInt(),
        )
        assertTrue(
            "ION has no leftover ink at the pre-scroll screen position",
            !hasInk(ion, oldScreenBbox),
        )

        // New screen position (docY - scrollY) must have the surviving stroke.
        val newScreenBbox = Rect(
            (keepStroke.minX - 4f).toInt(),
            (keepStroke.minY - scrollY - 4f).toInt(),
            (keepStroke.maxX + 4f).toInt(),
            (keepStroke.maxY - scrollY + 4f).toInt(),
        )
        assertTrue(
            "ION has the surviving stroke at its post-scroll screen position (darkest in $newScreenBbox: ${darkestPixel(ion, newScreenBbox)})",
            hasInk(ion, newScreenBbox),
        )
    }

    // ── 5. Full erase-write-scroll-write cycle ────────────────────────────────

    @Test
    fun fullCycle_ionInvariantHoldsAtEveryCommit() {
        // 1. Write a cluster of strokes on one line.
        val targets = listOf(100f, 200f, 300f)
        for (x in targets) view.injectStrokeForTest(stroke(x, 200f))
        view.flushFrameCallbacksForTest()

        // 2. Erase the middle one.
        val middle = view.getStrokes().sortedBy { (it.minX + it.maxX) / 2f }[1]
        view.removeStrokes(setOf(middle.strokeId))
        view.flushFrameCallbacksForTest()
        assertTrue(
            "after mid-erase, ION matches host",
            pixelsEqual(fake.ionBuffer()!!, view.contentBitmapForTest()!!, fullRect()),
        )

        // 3. Write a replacement stroke in the vacated middle position, then
        //    trigger another commit (erase the right stroke) to force a sync.
        view.injectStrokeForTest(stroke(200f, 200f))
        view.flushFrameCallbacksForTest()

        val rightmost = view.getStrokes().maxByOrNull { (it.minX + it.maxX) / 2f }!!
        view.removeStrokes(setOf(rightmost.strokeId))
        view.flushFrameCallbacksForTest()
        assertTrue(
            "after replacement-stroke + right-erase, ION matches host",
            pixelsEqual(fake.ionBuffer()!!, view.contentBitmapForTest()!!, fullRect()),
        )

        // 4. Scroll and verify again.
        view.scrollToForTest(80f)
        view.flushFrameCallbacksForTest()
        assertTrue(
            "after scroll, ION matches host",
            pixelsEqual(fake.ionBuffer()!!, view.contentBitmapForTest()!!, fullRect()),
        )
    }

    // ── 6. Force-refresh contract: erase must request EPD-level refresh ───────

    @Test
    fun erasePathRequestsForceRefresh() {
        view.injectStrokeForTest(stroke(150f, 200f))
        view.flushFrameCallbacksForTest()
        val target = view.getStrokes().single()

        val mark = fake.mark()
        view.removeStrokes(setOf(target.strokeId))
        view.flushFrameCallbacksForTest()

        // The erase path schedules forcedRefreshCallback, which calls
        // refreshOverlay(force=true). Without this, the daemon's ION overlay
        // keeps the pre-erase pixels on screen even though the shadow buffer
        // is fresh.
        assertTrue(
            "erase must schedule at least one syncOverlay(force=true)",
            fake.anySyncSince(mark) { it.force },
        )
    }

    // ── 7. Scroll contract: scroll syncs, force=false is the expected cadence ─

    @Test
    fun scrollPathSyncsOverlayWithoutForcedEpdRefresh() {
        view.injectStrokeForTest(stroke(100f, 300f))
        view.flushFrameCallbacksForTest()

        val mark = fake.mark()
        view.scrollToForTest(120f)
        view.flushFrameCallbacksForTest()

        val scrollSyncs = fake.calls.subList(mark, fake.calls.size)
        assertTrue("scroll must produce a sync", scrollSyncs.isNotEmpty())
        assertEquals(
            "scroll sync uses force=false — SurfaceFlinger's compose drives the EPD",
            scrollSyncs.count { !it.force },
            scrollSyncs.size,
        )
    }
}
