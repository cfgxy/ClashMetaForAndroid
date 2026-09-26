package com.github.kr328.clash.design.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 二维码位图渲染。
 *
 * 固定使用黑色模块 + 白色背景，不跟随深浅色主题，避免深色模式下对比度不足导致扫描失败。
 * 传入内容可能含用户 token，禁止在本文件任何位置输出到日志。
 */
object QrCode {
    private const val QUIET_ZONE_MODULES = 2

    /**
     * 将 [content] 编码为边长 [size] 像素的二维码位图。
     *
     * @throws com.google.zxing.WriterException 内容过长无法编码时抛出
     */
    fun encodeToBitmap(content: String, size: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to Charsets.UTF_8.name(),
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
        )

        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)

        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)

        for (y in 0 until height) {
            val offset = y * width

            for (x in 0 until width) {
                pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }
}
