package com.sboard.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.sboard.app.core.i18n.tr

/** QR scanner (ZXing, fully offline). Returns a launcher; the scanned text is passed to [onResult]. */
@Composable
fun rememberQrScanner(onResult: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(onResult)
    }
    return {
        launcher.launch(ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(tr("Наведите камеру на QR-код 2FA"))
            setBeepEnabled(false)
            setOrientationLocked(false)
        })
    }
}
