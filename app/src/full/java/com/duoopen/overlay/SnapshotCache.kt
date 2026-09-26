package com.duoopen.overlay

import android.graphics.Bitmap
import android.os.SystemClock

/**
 * The last screenshot taken of each panel kind, so a panel that has just
 * switched on can start frosted *immediately* — before the ~0.3 s it takes
 * the system to hand back a fresh capture of a waking panel — using the
 * picture it showed last time. Under heavy frost the difference is
 * invisible, and the fresh capture replaces it as soon as it lands.
 */
class SnapshotCache(private val maxAgeMs: Long) {
    private class Entry(val bitmap: Bitmap, val at: Long)

    private val entries = HashMap<Boolean, Entry>()

    fun put(innerPanel: Boolean, bitmap: Bitmap) {
        entries[innerPanel] = Entry(bitmap, SystemClock.uptimeMillis())
    }

    /** A recent, same-sized picture of this panel kind, or null. */
    fun get(innerPanel: Boolean, width: Int, height: Int): Bitmap? {
        val e = entries[innerPanel] ?: return null
        val b = e.bitmap
        if (b.isRecycled || b.width != width || b.height != height) return null
        if (SystemClock.uptimeMillis() - e.at > maxAgeMs) return null
        return b
    }

    fun ageMs(innerPanel: Boolean): Long? = entries[innerPanel]?.let { SystemClock.uptimeMillis() - it.at }
}
