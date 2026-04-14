package com.writer.storage

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Groups checkpoints into editing sessions based on time gaps.
 * A gap of more than [SESSION_GAP_MS] between consecutive checkpoints
 * starts a new session.
 */
object SessionGrouping {

    private const val SESSION_GAP_MS = 30 * 60_000L // 30 minutes

    data class Session(
        val checkpoints: List<VersionHistory.Checkpoint>,
        val startTime: Long,
        val endTime: Long,
        val label: String,
    )

    fun group(
        checkpoints: List<VersionHistory.Checkpoint>,
        gapMs: Long = SESSION_GAP_MS,
    ): List<Session> {
        if (checkpoints.isEmpty()) return emptyList()

        val sorted = checkpoints.sortedBy { it.timestamp }
        val sessions = mutableListOf<Session>()
        var currentGroup = mutableListOf(sorted[0])

        for (i in 1 until sorted.size) {
            val gap = sorted[i].timestamp - sorted[i - 1].timestamp
            if (gap > gapMs) {
                sessions.add(buildSession(currentGroup))
                currentGroup = mutableListOf(sorted[i])
            } else {
                currentGroup.add(sorted[i])
            }
        }
        sessions.add(buildSession(currentGroup))
        return sessions
    }

    private fun buildSession(checkpoints: List<VersionHistory.Checkpoint>): Session {
        val start = checkpoints.first().timestamp
        val end = checkpoints.last().timestamp
        return Session(
            checkpoints = checkpoints,
            startTime = start,
            endTime = end,
            label = formatLabel(start, end, checkpoints.size),
        )
    }

    private fun formatLabel(startMs: Long, endMs: Long, count: Int): String {
        val dateFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        val startStr = dateFmt.format(Date(startMs))
        val endStr = dateFmt.format(Date(endMs))

        val dayPrefix = formatDayPrefix(startMs)
        val timeRange = if (startStr == endStr) startStr else "$startStr – $endStr"
        return "$dayPrefix $timeRange ($count)"
    }

    private fun formatDayPrefix(timestampMs: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = timestampMs }

        return when {
            isSameDay(now, then) -> "Today"
            isYesterday(now, then) -> "Yesterday"
            else -> SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(timestampMs))
        }
    }

    private fun isSameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    private fun isYesterday(now: Calendar, then: Calendar): Boolean {
        val yesterday = now.clone() as Calendar
        yesterday.add(Calendar.DAY_OF_YEAR, -1)
        return isSameDay(yesterday, then)
    }
}
