package com.pbrajesh.mobile.nostrchat.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

@Composable
fun QrCode(content: String, modifier: Modifier = Modifier) {
    val bitmap = remember(content) { renderQr(content) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "QR code for $content",
        filterQuality = FilterQuality.None,
        modifier = modifier,
    )
}

private fun renderQr(content: String): Bitmap {
    val hints = mapOf(
        EncodeHintType.MARGIN to 2,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
    )
    // Size 0 returns one pixel per module; Image scales it up without blurring.
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        if (matrix.get(i % matrix.width, i / matrix.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}
