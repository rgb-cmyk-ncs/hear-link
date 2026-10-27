package com.sergei.hearlink

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Hands the camera app one file to write its photo into, so the text can be read at full
 * resolution. Only that one file is reachable, and only by the app it is given to.
 */
class ShotProvider : ContentProvider() {
    override fun onCreate() = true

    private fun file(): File = shotFile(context!!.cacheDir)

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = file()
        f.parentFile?.mkdirs()
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }

    override fun getType(uri: Uri) = "image/jpeg"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map { if (it == OpenableColumns.SIZE) file().length() else NAME }.toTypedArray())
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "com.sergei.hearlink.shots"
        private const val NAME = "photo.jpg"
        val URI: Uri = Uri.parse("content://$AUTHORITY/$NAME")
        fun shotFile(cache: File) = File(File(cache, "shots"), NAME)
    }
}
