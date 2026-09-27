package com.docscanner.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.docscanner.app.data.DocRepository
import com.docscanner.app.databinding.ActivityFilterBinding
import com.docscanner.app.util.ImageUtils
import com.docscanner.app.util.ScanSession

class FilterActivity : AppCompatActivity() {

    private lateinit var b: ActivityFilterBinding
    private var base: Bitmap? = null
    private var current: Bitmap? = null
    private var activeFilter = ImageUtils.Filter.ORIGINAL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityFilterBinding.inflate(layoutInflater)
        setContentView(b.root)

        base = ScanSession.warpedImage
        if (base == null) { finish(); return }

        val editing = DocRepository.editingExistingPage != null
        b.title.text = if (editing) "Re-apply Filter" else "Enhance Page"
        b.btnSave.text = if (editing) "Save Edit" else "Add Page"

        b.btnBack.setOnClickListener { onBack() }
        b.btnSave.setOnClickListener { save() }
        b.fOriginal.setOnClickListener { apply(ImageUtils.Filter.ORIGINAL) }
        b.fMagic.setOnClickListener { apply(ImageUtils.Filter.MAGIC) }
        b.fGray.setOnClickListener { apply(ImageUtils.Filter.GRAYSCALE) }
        b.fBw.setOnClickListener { apply(ImageUtils.Filter.BW) }

        apply(ImageUtils.Filter.ORIGINAL)
    }

    private fun apply(f: ImageUtils.Filter) {
        activeFilter = f
        val src = base ?: return
        current = ImageUtils.applyFilter(src, f)
        b.preview.setImageBitmap(current)
        highlight()
    }

    private fun highlight() {
        val map = listOf(
            b.fOriginal to ImageUtils.Filter.ORIGINAL,
            b.fMagic to ImageUtils.Filter.MAGIC,
            b.fGray to ImageUtils.Filter.GRAYSCALE,
            b.fBw to ImageUtils.Filter.BW
        )
        map.forEach { (btn: Button, f) ->
            btn.setTextColor(if (f == activeFilter) Color.parseColor("#10B981") else Color.parseColor("#CBD5E1"))
        }
    }

    private fun save() {
        val out = current ?: base ?: return
        val asPng = activeFilter == ImageUtils.Filter.ORIGINAL
        val path = DocRepository.savePageBitmap(out, asPng)

        val editIdx = DocRepository.editingExistingPage
        if (editIdx != null) {
            val doc = DocRepository.findDoc(DocRepository.viewingDocId)
            if (doc != null && editIdx in doc.pages.indices) {
                runCatching { java.io.File(doc.pages[editIdx]).delete() }
                doc.pages[editIdx] = path
            }
            DocRepository.editingExistingPage = null
            DocRepository.save()
            goToDocument()
        } else {
            DocRepository.activeSessionPages.add(path)
            // back to camera to continue scanning
            startActivity(Intent(this, CameraActivity::class.java))
            finish()
        }
    }

    private fun onBack() {
        if (DocRepository.filterViewOrigin == "document" && DocRepository.editingExistingPage != null) {
            DocRepository.editingExistingPage = null
            goToDocument()
        } else {
            finish() // returns to crop/camera stack
        }
    }

    private fun goToDocument() {
        startActivity(Intent(this, DocumentActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        })
        finish()
    }
}
