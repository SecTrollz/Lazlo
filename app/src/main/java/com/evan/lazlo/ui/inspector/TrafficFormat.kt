package com.evan.lazlo.ui.inspector

import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.max

/**
 * Pure formatting helpers for the traffic log list. Kept separate from
 * the Composable/ViewModel so the "how do we describe this entry"
 * logic can be unit tested on the JVM without any Android/Compose
 * dependency.
 */
object TrafficFormat {

    /** "just now", "12s ago", "5m ago", "3h ago", "2d ago" — coarse on purpose, this is a log list, not a clock. */
    fun relativeTime(entryTime: Instant, now: Instant): String {
        val seconds = max(0L, Duration.between(entryTime, now).seconds)
        return when {
            seconds < 5 -> "just now"
            seconds < 60 -> "${seconds}s ago"
            seconds < 3600 -> "${seconds / 60}m ago"
            seconds < 86_400 -> "${seconds / 3600}h ago"
            else -> "${seconds / 86_400}d ago"
        }
    }

    /** "0 B", "512 B", "4.2 KB", "1.1 MB" — one decimal place above the byte scale. */
    fun humanBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.1f MB", mb)
    }

    /** Short status label for the list row: the code if we saw one, "—" if the exchange never got a parsed response line. */
    fun statusLabel(status: Int?): String = status?.toString() ?: "—"
}
