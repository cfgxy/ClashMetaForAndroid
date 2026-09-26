package com.github.kr328.clash.design.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

/**
 * 把位图保存到系统相册（Pictures 集合）。
 *
 * Android 10（Q）及以上走 MediaStore 分区存储，无需运行时权限；
 * Android 9 及以下写入公共 Pictures 目录，需要 `WRITE_EXTERNAL_STORAGE`。
 */
object ImageStore {
    private const val SUB_DIRECTORY = "Clash"
    private const val MIME_TYPE = "image/png"
    private val RELATIVE_PATH = "${Environment.DIRECTORY_PICTURES}/$SUB_DIRECTORY"

    /**
     * 保存 [bitmap] 为 PNG，文件名取 [displayName]（不含扩展名）。
     *
     * @return 保存成功返回 true；失败返回 false（调用方只给动作级提示，不回显任何内容）
     */
    fun savePicture(context: Context, bitmap: Bitmap, displayName: String): Boolean {
        val fileName = "$displayName.png"

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, bitmap, fileName)
            } else {
                saveToPublicDirectory(context, bitmap, fileName)
            }
        } catch (e: Exception) {
            // 异常信息可能含文件路径，不记录内容，仅返回失败
            false
        }
    }

    private fun saveViaMediaStore(context: Context, bitmap: Bitmap, fileName: String): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false

        val compressed = try {
            resolver.openOutputStream(uri)?.use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            } ?: false
        } catch (e: Exception) {
            false
        }

        if (!compressed) {
            resolver.delete(uri, null, null)

            return false
        }

        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null,
        )

        return true
    }

    private fun saveToPublicDirectory(context: Context, bitmap: Bitmap, fileName: String): Boolean {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            SUB_DIRECTORY,
        )

        if (!directory.isDirectory && !directory.mkdirs()) {
            return false
        }

        val file = File(directory, fileName)

        val compressed = FileOutputStream(file).use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }

        if (!compressed) {
            file.delete()

            return false
        }

        // 让文件立刻在系统相册与文件管理器中可见
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(MIME_TYPE), null)

        return true
    }
}
