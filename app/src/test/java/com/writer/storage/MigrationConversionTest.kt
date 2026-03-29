package com.writer.storage

import com.writer.model.proto.DocumentProto
import com.writer.view.ScreenMetrics
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Reads real documents from .migration/originals/, round-trips each through
 * v3 compact encoding, and reports size deltas and coordinate drift.
 *
 * Run: ./gradlew testDebugUnitTest --tests "com.writer.storage.MigrationConversionTest"
 */
class MigrationConversionTest {

    companion object {
        // Palma 2 Pro — the source device
        const val DENSITY = 1.875f
        const val SW_PALMA = 439
        const val W_PALMA = 824
        const val H_PALMA = 1648
    }

    @Before
    fun setUp() {
        ScreenMetrics.init(DENSITY, smallestWidthDp = SW_PALMA, widthPixels = W_PALMA, heightPixels = H_PALMA)
    }

    private fun originalsDir(): File {
        // Gradle test working dir is the module dir (app/), so go up one level
        val dir = File("../.migration/originals")
        if (!dir.exists()) {
            // Also try from project root (IDE runners)
            val alt = File(".migration/originals")
            if (alt.exists()) return alt
        }
        return dir
    }

    @Test
    fun reportMigrationDeltas() {
        val dir = originalsDir()
        if (!dir.exists()) {
            println("⚠ No originals directory found at ${dir.absolutePath} — skipping migration test")
            return
        }

        val files = dir.listFiles()?.filter { it.extension == "inkup" }?.sortedBy { it.name }
            ?: emptyList()
        if (files.isEmpty()) {
            println("⚠ No .inkup files found in ${dir.absolutePath}")
            return
        }

        println()
        println("=" .repeat(100))
        println("MIGRATION CONVERSION REPORT — ${files.size} documents")
        println("Device: Palma 2 Pro (${W_PALMA}×${H_PALMA}, density=$DENSITY, sw=${SW_PALMA}dp)")
        println("Line spacing: ${ScreenMetrics.lineSpacing}px, Top margin: ${ScreenMetrics.topMargin}px")
        println("=".repeat(100))
        println()
        println(String.format("%-50s %8s %8s %7s %6s %6s %10s %10s",
            "Document", "Orig", "V3", "Ratio", "Strks", "Pts", "MaxΔx(lu)", "MaxΔy(lu)"))
        println("-".repeat(100))

        var totalOrigBytes = 0L
        var totalV3Bytes = 0L
        var totalStrokes = 0
        var totalPoints = 0
        var globalMaxDriftX = 0.0
        var globalMaxDriftY = 0.0
        var errorCount = 0

        for (file in files) {
            try {
                val origBytes = file.readBytes()
                val origProto = DocumentProto.ADAPTER.decode(origBytes)

                val coordSystem = origProto.coordinate_system ?: 0
                val hasRuns = origProto.main?.strokes?.any { it.x_run != null } == true

                // Decode to domain, re-encode as v3
                val domain = origProto.toDomain()
                val v3Proto = domain.toProto()
                val v3Bytes = v3Proto.encode()

                // Decode v3 back to domain for comparison
                val v3Domain = v3Proto.toDomain()

                // Count strokes and points
                val mainStrokes = domain.main.strokes.size
                val cueStrokes = domain.cue.strokes.size
                val strokeCount = mainStrokes + cueStrokes
                val pointCount = domain.main.strokes.sumOf { it.points.size } +
                    domain.cue.strokes.sumOf { it.points.size }

                // Compute max coordinate drift in line-units
                var maxDriftX = 0.0
                var maxDriftY = 0.0
                val ls = ScreenMetrics.lineSpacing

                for ((orig, conv) in domain.main.strokes.zip(v3Domain.main.strokes)) {
                    for ((op, cp) in orig.points.zip(conv.points)) {
                        val dx = abs((op.x - cp.x).toDouble()) / ls
                        val dy = abs((op.y - cp.y).toDouble()) / ls
                        if (dx > maxDriftX) maxDriftX = dx
                        if (dy > maxDriftY) maxDriftY = dy
                    }
                }
                for ((orig, conv) in domain.cue.strokes.zip(v3Domain.cue.strokes)) {
                    for ((op, cp) in orig.points.zip(conv.points)) {
                        val dx = abs((op.x - cp.x).toDouble()) / ls
                        val dy = abs((op.y - cp.y).toDouble()) / ls
                        if (dx > maxDriftX) maxDriftX = dx
                        if (dy > maxDriftY) maxDriftY = dy
                    }
                }

                val ratio = if (origBytes.size > 0) v3Bytes.size.toDouble() / origBytes.size else 0.0
                val name = file.nameWithoutExtension.take(48)
                val format = if (hasRuns) "v3" else if (coordSystem == 1) "v2" else "v1"

                println(String.format("%-50s %7dB %7dB %6.2fx %6d %6d %10.4f %10.4f  [%s]",
                    name, origBytes.size, v3Bytes.size, ratio, strokeCount, pointCount,
                    maxDriftX, maxDriftY, format))

                totalOrigBytes += origBytes.size
                totalV3Bytes += v3Bytes.size
                totalStrokes += strokeCount
                totalPoints += pointCount
                if (maxDriftX > globalMaxDriftX) globalMaxDriftX = maxDriftX
                if (maxDriftY > globalMaxDriftY) globalMaxDriftY = maxDriftY

                // Assert no drift exceeds 0.01 line-units (the quantization step)
                assert(maxDriftX <= 0.011) {
                    "${file.name}: X drift ${maxDriftX} exceeds 0.01 line-units"
                }
                assert(maxDriftY <= 0.011) {
                    "${file.name}: Y drift ${maxDriftY} exceeds 0.01 line-units"
                }

            } catch (e: Exception) {
                println(String.format("%-50s  *** ERROR: %s", file.nameWithoutExtension.take(48), e.message))
                errorCount++
            }
        }

        println("-".repeat(100))
        val totalRatio = if (totalOrigBytes > 0) totalV3Bytes.toDouble() / totalOrigBytes else 0.0
        println(String.format("%-50s %7dB %7dB %6.2fx %6d %6d %10.4f %10.4f",
            "TOTAL", totalOrigBytes, totalV3Bytes, totalRatio, totalStrokes, totalPoints,
            globalMaxDriftX, globalMaxDriftY))
        println()
        if (errorCount > 0) {
            println("⚠ $errorCount files failed to convert")
        }
        println("Max coordinate drift: X=${String.format("%.4f", globalMaxDriftX)} lu, Y=${String.format("%.4f", globalMaxDriftY)} lu")
        println("Quantization step: 0.01 lu = ${String.format("%.2f", 0.01 * ScreenMetrics.lineSpacing)}px")
        println()
    }
}
