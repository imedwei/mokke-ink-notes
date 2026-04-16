package com.writer.ui.writing

import com.writer.model.DiagramArea
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.view.ScreenMetrics
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes a bug report containing device info, document state,
 * recent stroke history, and processing decisions.
 *
 * The report is a self-contained JSON file that can be loaded in
 * unit tests to replay the stroke sequence through the pipeline.
 */
object BugReport {

    fun serialize(
        eventSnapshot: StrokeEventLog.Snapshot,
        activeStrokes: List<InkStroke>,
        diagramAreas: List<DiagramArea>,
        lineTextCache: Map<Int, String>,
        userDescription: String = ""
    ): JSONObject {
        return JSONObject().apply {
            put("version", 1)
            put("timestampMs", System.currentTimeMillis())
            put("userDescription", userDescription)

            // Device / screen metrics
            put("device", JSONObject().apply {
                put("model", android.os.Build.MODEL)
                put("manufacturer", android.os.Build.MANUFACTURER)
                put("osVersion", android.os.Build.VERSION.SDK_INT)
                put("density", ScreenMetrics.density.toDouble())
                put("lineSpacing", ScreenMetrics.lineSpacing.toDouble())
                put("topMargin", ScreenMetrics.topMargin.toDouble())
                put("isLargeScreen", ScreenMetrics.isLargeScreen)
            })

            // Recent raw strokes from ring buffer
            put("recentStrokes", JSONArray().apply {
                for (entry in eventSnapshot.strokes) {
                    put(JSONObject().apply {
                        put("index", entry.index)
                        put("timestampMs", entry.timestampMs)
                        put("points", serializePoints(entry.points))
                    })
                }
            })

            // Processing events
            put("processingEvents", JSONArray().apply {
                for (event in eventSnapshot.events) {
                    put(JSONObject().apply {
                        put("strokeIndex", event.strokeIndex)
                        put("type", event.type.name)
                        put("detail", event.detail)
                        put("timestampMs", event.timestampMs)
                        if (event.elapsedMs >= 0) put("elapsedMs", event.elapsedMs)
                    })
                }
            })

            // Performance summary
            put("perfSummary", perfSummary(eventSnapshot.events))

            // Current document state
            put("documentState", JSONObject().apply {
                put("strokeCount", activeStrokes.size)
                put("strokes", JSONArray().apply {
                    for (stroke in activeStrokes) {
                        put(JSONObject().apply {
                            put("strokeId", stroke.strokeId)
                            put("strokeType", stroke.strokeType.name)
                            put("isGeometric", stroke.isGeometric)
                            put("pointCount", stroke.points.size)
                            put("points", serializePoints(stroke.points))
                        })
                    }
                })
                put("diagramAreas", JSONArray().apply {
                    for (area in diagramAreas) {
                        put(JSONObject().apply {
                            put("startLineIndex", area.startLineIndex)
                            put("heightInLines", area.heightInLines)
                        })
                    }
                })
                put("lineTextCache", JSONObject().apply {
                    for ((lineIdx, text) in lineTextCache) {
                        put(lineIdx.toString(), text)
                    }
                })
            })

            // Performance counters (rolling window stats)
            put("perfCounters", JSONObject().apply {
                for ((metric, snap) in PerfCounters.snapshot()) {
                    if (snap.count > 0) {
                        put(metric.label, JSONObject().apply {
                            put("count", snap.count)
                            put("lastMs", snap.lastMs)
                            put("p50Ms", snap.p50Ms)
                            put("p95Ms", snap.p95Ms)
                            put("maxMs", snap.maxMs)
                        })
                    }
                }
            })
        }
    }

    private fun perfSummary(events: List<StrokeEventLog.ProcessingEvent>): JSONObject {
        val timed = events.filter { it.elapsedMs >= 0 }.map { it.elapsedMs }
        if (timed.isEmpty()) return JSONObject().apply { put("samples", 0) }
        val sorted = timed.sorted()
        return JSONObject().apply {
            put("samples", sorted.size)
            put("p50Ms", sorted[sorted.size / 2])
            put("p95Ms", sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.lastIndex)])
            put("maxMs", sorted.last())
            put("over50ms", sorted.count { it > 50 })
        }
    }

    private fun serializePoints(points: List<StrokePoint>): JSONArray {
        return JSONArray().apply {
            for (pt in points) {
                put(JSONObject().apply {
                    put("x", pt.x.toDouble())
                    put("y", pt.y.toDouble())
                    put("pressure", pt.pressure.toDouble())
                    put("timestamp", pt.timestamp)
                })
            }
        }
    }
}
