package com.duoopen.settings

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.duoopen.overlay.FoldOverlayService

/** Read-only opt-in gate for the local shell helper, protected by DUMP permission. */
class DisplayBridgeProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        require(uri.path == "/status")
        val enabled = DuoSettings.config.value.keepBothDisplaysAwake &&
            context?.let { FoldOverlayService.isEnabled(it) } == true
        return MatrixCursor(arrayOf("enabled")).apply { addRow(arrayOf(if (enabled) 1 else 0)) }
    }

    override fun getType(uri: Uri) = "vnd.android.cursor.item/vnd.duoopen.displaybridge"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
}
