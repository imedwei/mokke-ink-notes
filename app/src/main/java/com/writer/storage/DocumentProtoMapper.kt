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

// ── Domain → Proto ───────────────────────────────────────────────────────

fun DocumentData.toProto(): DocumentProto = DocumentProto(
    main = main.toProto(),
    cue = if (cue.strokes.isNotEmpty() || cue.lineTextCache.isNotEmpty() ||
        cue.diagramAreas.isNotEmpty()) cue.toProto() else null,
    scroll_offset_y = scrollOffsetY,
    highest_line_index = highestLineIndex,
    current_line_index = currentLineIndex,
    user_renamed = userRenamed
)

fun ColumnData.toProto(): ColumnDataProto = ColumnDataProto(
    strokes = strokes.map { it.toProto() },
    line_text_cache = lineTextCache,
    ever_hidden_lines = everHiddenLines.toList(),
    diagram_areas = diagramAreas.map { it.toProto() }
)

fun InkStroke.toProto(): InkStrokeProto = InkStrokeProto(
    stroke_id = strokeId,
    stroke_width = strokeWidth,
    points = points.map { it.toProto() },
    stroke_type = strokeType.toProto(),
    is_geometric = isGeometric
)

fun StrokePoint.toProto(): StrokePointProto = StrokePointProto(
    x = x,
    y = y,
    pressure = pressure,
    timestamp = timestamp
)

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

fun DocumentProto.toDomain(): DocumentData = DocumentData(
    main = main?.toDomain() ?: ColumnData(),
    cue = cue?.toDomain() ?: ColumnData(),
    scrollOffsetY = scroll_offset_y ?: 0f,
    highestLineIndex = highest_line_index ?: 0,
    currentLineIndex = current_line_index ?: 0,
    userRenamed = user_renamed ?: false
)

fun ColumnDataProto.toDomain(): ColumnData = ColumnData(
    strokes = strokes.map { it.toDomain() },
    lineTextCache = line_text_cache,
    everHiddenLines = ever_hidden_lines.toSet(),
    diagramAreas = diagram_areas.map { it.toDomain() }
)

fun InkStrokeProto.toDomain(): InkStroke = InkStroke(
    strokeId = stroke_id ?: "",
    points = points.map { it.toDomain() },
    strokeWidth = stroke_width ?: 3f,
    isGeometric = is_geometric ?: false,
    strokeType = stroke_type?.toDomain() ?: StrokeType.FREEHAND
)

fun StrokePointProto.toDomain(): StrokePoint = StrokePoint(
    x = x ?: 0f,
    y = y ?: 0f,
    pressure = pressure ?: 1f,
    timestamp = timestamp ?: 0L
)

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
