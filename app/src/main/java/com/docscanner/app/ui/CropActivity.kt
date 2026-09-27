package com.docscanner.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.docscanner.app.databinding.ActivityCropBinding
import com.docscanner.app.util.ImageUtils
import com.docscanner.app.util.ScanSession

class CropActivity : AppCompatActivity() {

    private lateinit var b: ActivityCropBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCropBinding.inflate(layoutInflater)
        setContentView(b.root)

        val raw = ScanSession.rawImage
        if (raw == null) { finish(); return }
        b.cropOverlay.post { b.cropOverlay.setBitmap(raw) }

        b.btnCancel.setOnClickListener { finish() }
        b.btnFullImage.setOnClickListener { b.cropOverlay.resetCorners() }
        b.btnNext.setOnClickListener { doCrop() }
    }

    private fun doCrop() {
        val raw = ScanSession.rawImage ?: return
        val corners = b.cropOverlay.getCornersNormalized()
        val warped = try {
            ImageUtils.perspectiveCrop(raw, corners)
        } catch (e: Exception) {
            Toast.makeText(this, "Crop failed, using full image", Toast.LENGTH_SHORT).show()
            raw
        }
        ScanSession.warpedImage = warped
        startActivity(Intent(this, FilterActivity::class.java))
        finish()
    }
}
