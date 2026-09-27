package com.docscanner.app.util

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** On-device OCR using ML Kit (replaces Tesseract.js from the web app). */
object OcrHelper {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognizeFile(path: String): String {
        val bmp = BitmapFactory.decodeFile(path) ?: return ""
        val image = InputImage.fromBitmap(bmp, 0)
        return suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it.text) }
                .addOnFailureListener { cont.resume("[OCR failed: ${it.message}]") }
        }
    }
}
