package com.writer.storage

import com.writer.model.ColumnData
import com.writer.model.DiagramArea
import com.writer.model.DocumentData
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.StrokeType
import com.writer.model.proto.ColumnDataProto
import com.writer.model.proto.DiagramAreaProto
import com.writer.model.proto.DocumentProto
import com.writer.model.proto.InkStrokeProto
import com.writer.model.proto.StrokePointProto
import com.writer.model.proto.StrokeTypeProto
import com.writer.view.ScreenMetrics

// Coordinate system constants matching document.proto
private const val COORD_SYSTEM_LEGACY = 0
private const val COORD_SYSTEM_NORMALIZED = 1

// ── Domain → Proto ───────────────────────────────────────────────────────

fun DocumentData.toProto(): DocumentProto {
    val ls = ScreenMetrics.lineSpacing
    return DocumentProto(
        main = main.toProto(ls),
        cue = if (cue.strokes.isNotEmpty() || cue.lineTextCache.isNotEmpty() ||
            cue.diagramAreas.isNotEmpty()) cue.toProto(ls) else null,
        scroll_offset_y = scrollOffsetY / ls,
        highest_line_index = highestLineIndex,
        current_line_index = currentLineIndex,
        user_renamed = userRenamed,
        coordinate_system = COORD_SYSTEM_NORMALIZED
    )
}

private fun ColumnData.toProto(lineSpacing: Float): ColumnDataProto = ColumnDataProto(
    strokes = strokes.map { it.toProto(lineSpacing) },
    line_text_cache = lineTextCache,
    ever_hidden_lines = everHiddenLines.toList(),
    diagram_areas = diagramAreas.map { it.toProto() }
)

private fun InkStroke.toProto(lineSpacing: Float): InkStrokeProto {
    if (points.isEmpty()) {
        return InkStrokeProto(
            stroke_id = strokeId,
            stroke_width = strokeWidth,
            stroke_type = strokeType.toProto(),
            is_geometric = isGeometric
        )
    }
    val tm = ScreenMetrics.topMargin
    val xs = FloatArray(points.size) { points[it].x / lineSpacing }
    val ys = FloatArray(points.size) { (points[it].y - tm) / lineSpacing }
    val pressures = FloatArray(points.size) { points[it].pressure }
    val timestamps = LongArray(points.size) { points[it].timestamp }

    val hasTimestamps = !NumericRunEncoder.allZeroTimestamps(timestamps)
    val baseTimestamp = if (hasTimestamps) timestamps[0] else 0L

    return InkStrokeProto(
        stroke_id = strokeId,
        stroke_width = strokeWidth,  // Pen property — NOT normalized
        stroke_type = strokeType.toProto(),
        is_geometric = isGeometric,
        x_run = NumericRunEncoder.encodeCoordinates(xs),
        y_run = NumericRunEncoder.encodeCoordinates(ys),
        pressure_run = if (NumericRunEncoder.allDefaultPressure(pressures)) null
            else NumericRunEncoder.encodePressure(pressures),
        time_run = if (hasTimestamps) NumericRunEncoder.encodeTimestamps(timestamps, baseTimestamp) else null,
        stroke_timestamp = if (hasTimestamps) baseTimestamp else null
    )
}

fun DiagramArea.toProto(): DiagramAreaProto = DiagramAreaProto(
    id = id,
    start_line_index = startLineIndex,
    height_in_lines = heightInLines
)

fun StrokeType.toProto(): StrokeTypeProto = when (this) {
    StrokeType.FREEHAND -> StrokeTypeProto.FREEHAND
    StrokeType.LINE -> StrokeTypeProto.LINE
    StrokeType.ARROW_HEAD -> StrokeTypeProto.ARROW_HEAD
    StrokeType.ARROW_TAIL -> StrokeTypeProto.ARROW_TAIL
    StrokeType.ARROW_BOTH -> StrokeTypeProto.ARROW_BOTH
    StrokeType.ELBOW -> StrokeTypeProto.ELBOW
    StrokeType.ELBOW_ARROW_HEAD -> StrokeTypeProto.ELBOW_ARROW_HEAD
    StrokeType.ELBOW_ARROW_TAIL -> StrokeTypeProto.ELBOW_ARROW_TAIL
    StrokeType.ELBOW_ARROW_BOTH -> StrokeTypeProto.ELBOW_ARROW_BOTH
    StrokeType.ARC -> StrokeTypeProto.ARC
    StrokeType.ARC_ARROW_HEAD -> StrokeTypeProto.ARC_ARROW_HEAD
    StrokeType.ARC_ARROW_TAIL -> StrokeTypeProto.ARC_ARROW_TAIL
    StrokeType.ARC_ARROW_BOTH -> StrokeTypeProto.ARC_ARROW_BOTH
    StrokeType.ELLIPSE -> StrokeTypeProto.ELLIPSE
    StrokeType.RECTANGLE -> StrokeTypeProto.RECTANGLE
    StrokeType.ROUNDED_RECTANGLE -> StrokeTypeProto.ROUNDED_RECTANGLE
    StrokeType.TRIANGLE -> StrokeTypeProto.TRIANGLE
    StrokeType.DIAMOND -> StrokeTypeProto.DIAMOND
}

// ── Proto → Domain ───────────────────────────────────────────────────────

fun DocumentProto.toDomain(): DocumentData {
    val isNormalized = (coordinate_system ?: COORD_SYSTEM_LEGACY) == COORD_SYSTEM_NORMALIZED
    val ls = ScreenMetrics.lineSpacing
    val tm = ScreenMetrics.topMargin
    return DocumentData(
        main = main?.toDomain(isNormalized, ls, tm) ?: ColumnData(),
        cue = cue?.toDomain(isNormalized, ls, tm) ?: ColumnData(),
        scrollOffsetY = if (isNormalized) (scroll_offset_y ?: 0f) * ls else (scroll_offset_y ?: 0f),
        highestLineIndex = highest_line_index ?: 0,
        currentLineIndex = current_line_index ?: 0,
        userRenamed = user_renamed ?: false
    )
}

private fun ColumnDataProto.toDomain(
    isNormalized: Boolean, lineSpacing: Float, topMargin: Float
): ColumnData = ColumnData(
    strokes = strokes.map { it.toDomain(isNormalized, lineSpacing, topMargin) },
    lineTextCache = line_text_cache,
    everHiddenLines = ever_hidden_lines.toSet(),
    diagramAreas = diagram_areas.map { it.toDomain() }
)

private fun InkStrokeProto.toDomain(
    isNormalized: Boolean, lineSpacing: Float, topMargin: Float
): InkStroke {
    // v3 compact encoding: decode from runs when x_run is present
    val decodedPoints = if (x_run != null) {
        decodeFromRuns(lineSpacing, topMargin)
    } else {
        points.map { it.toDomain(isNormalized, lineSpacing, topMargin) }
    }
    return InkStroke(
        strokeId = stroke_id ?: "",
        points = decodedPoints,
        strokeWidth = stroke_width ?: 3f,  // Pen property — NOT denormalized
        isGeometric = is_geometric ?: false,
        strokeType = stroke_type?.toDomain() ?: StrokeType.FREEHAND
    )
}

private fun InkStrokeProto.decodeFromRuns(
    lineSpacing: Float, topMargin: Float
): List<StrokePoint> {
    val xs = NumericRunEncoder.decode(x_run!!)
    require(y_run != null) { "y_run must be present when x_run is present" }
    val ys = NumericRunEncoder.decode(y_run!!)
    val pressures = pressure_run?.let { NumericRunEncoder.decode(it) }
    val timestamps = time_run?.let { run ->
        val baseMs = if ((stroke_timestamp ?: 0L) != 0L) {
            stroke_timestamp!!  // v4: proper int64 base
        } else {
            NumericRunEncoder.legacyTimestampBaseMs(run)  // v3: float offset fallback
        }
        NumericRunEncoder.decodeTimestamps(run, baseMs)
    }
    val count = xs.size

    require(ys.size == count) { "y_run length ${ys.size} != x_run length $count" }
    if (pressures != null) require(pressures.size == count) { "pressure_run length ${pressures.size} != x_run length $count" }
    if (timestamps != null) require(timestamps.size == count) { "time_run length ${timestamps.size} != x_run length $count" }

    return List(count) { i ->
        StrokePoint(
            x = xs[i] * lineSpacing,
            y = topMargin + ys[i] * lineSpacing,
            pressure = pressures?.get(i) ?: 1f,
            timestamp = timestamps?.get(i) ?: 0L
        )
    }
}

private fun StrokePointProto.toDomain(
    isNormalized: Boolean, lineSpacing: Float, topMargin: Float
): StrokePoint = if (isNormalized) {
    StrokePoint(
        x = (x ?: 0f) * lineSpacing,
        y = topMargin + (y ?: 0f) * lineSpacing,
        pressure = pressure ?: 1f,
        timestamp = timestamp ?: 0L
    )
} else {
    StrokePoint(
        x = x ?: 0f,
        y = y ?: 0f,
        pressure = pressure ?: 1f,
        timestamp = timestamp ?: 0L
    )
}

fun DiagramAreaProto.toDomain(): DiagramArea = DiagramArea(
    id = id ?: "",
    startLineIndex = start_line_index ?: 0,
    heightInLines = height_in_lines ?: 1
)

fun StrokeTypeProto.toDomain(): StrokeType = when (this) {
    StrokeTypeProto.FREEHAND -> StrokeType.FREEHAND
    StrokeTypeProto.LINE -> StrokeType.LINE
    StrokeTypeProto.ARROW_HEAD -> StrokeType.ARROW_HEAD
    StrokeTypeProto.ARROW_TAIL -> StrokeType.ARROW_TAIL
    StrokeTypeProto.ARROW_BOTH -> StrokeType.ARROW_BOTH
    StrokeTypeProto.ELBOW -> StrokeType.ELBOW
    StrokeTypeProto.ELBOW_ARROW_HEAD -> StrokeType.ELBOW_ARROW_HEAD
    StrokeTypeProto.ELBOW_ARROW_TAIL -> StrokeType.ELBOW_ARROW_TAIL
    StrokeTypeProto.ELBOW_ARROW_BOTH -> StrokeType.ELBOW_ARROW_BOTH
    StrokeTypeProto.ARC -> StrokeType.ARC
    StrokeTypeProto.ARC_ARROW_HEAD -> StrokeType.ARC_ARROW_HEAD
    StrokeTypeProto.ARC_ARROW_TAIL -> StrokeType.ARC_ARROW_TAIL
    StrokeTypeProto.ARC_ARROW_BOTH -> StrokeType.ARC_ARROW_BOTH
    StrokeTypeProto.ELLIPSE -> StrokeType.ELLIPSE
    StrokeTypeProto.RECTANGLE -> StrokeType.RECTANGLE
    StrokeTypeProto.ROUNDED_RECTANGLE -> StrokeType.ROUNDED_RECTANGLE
    StrokeTypeProto.TRIANGLE -> StrokeType.TRIANGLE
    StrokeTypeProto.DIAMOND -> StrokeType.DIAMOND
}
