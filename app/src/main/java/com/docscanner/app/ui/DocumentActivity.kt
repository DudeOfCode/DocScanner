package com.docscanner.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import com.docscanner.app.data.DocRepository
import com.docscanner.app.databinding.ActivityDocumentBinding
import com.docscanner.app.util.ImageUtils
import com.docscanner.app.util.OcrHelper
import com.docscanner.app.util.PdfExporter
import com.docscanner.app.util.ScanSession
import java.io.File

class DocumentActivity : AppCompatActivity() {

    private lateinit var b: ActivityDocumentBinding
    private lateinit var adapter: PagesAdapter
    private val selected = linkedSetOf<Int>()

    private val pickImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (!uris.isNullOrEmpty()) importImages(uris)
    }
    private val pickPdf = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { importPdf(it) }
    }

    private fun doc() = DocRepository.findDoc(DocRepository.viewingDocId)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDocumentBinding.inflate(layoutInflater)
        setContentView(b.root)

        val d = doc()
        if (d == null) { finish(); return }
        b.docTitle.setText(d.title)
        b.docTitle.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d2: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d2: Int) {}
            override fun afterTextChanged(s: Editable?) {
                doc()?.let { it.title = s?.toString()?.ifBlank { "Untitled" } ?: "Untitled"; DocRepository.save() }
            }
        })

        adapter = PagesAdapter(
            pages = { doc()?.pages ?: emptyList() },
            selected = { selected },
            onToggleSelect = { pos, checked -> if (checked) selected.add(pos) else selected.remove(pos); refreshSelection() },
            onMove = { pos, dir -> movePage(pos, dir) },
            onCrop = { pos -> editPage(pos, crop = true) },
            onFilter = { pos -> editPage(pos, crop = false) },
            onRotate = { pos -> rotatePage(pos) },
            onDelete = { pos -> deletePage(pos) }
        )
        b.pagesList.layoutManager = LinearLayoutManager(this)
        b.pagesList.adapter = adapter

        b.btnBack.setOnClickListener { goDashboard() }
        b.btnScan.setOnClickListener {
            DocRepository.activeSessionPages.clear()
            startActivity(Intent(this, CameraActivity::class.java))
        }
        b.btnAddImages.setOnClickListener { pickImages.launch("image/*") }
        b.btnAddPdf.setOnClickListener { pickPdf.launch("application/pdf") }
        b.btnOcr.setOnClickListener { runOcr() }
        b.btnExport.setOnClickListener { exportPdf(doc()?.pages ?: emptyList(), doc()?.title ?: "document") }
        b.btnDeleteDoc.setOnClickListener { confirmDeleteDoc() }

        b.btnSelectAll.setOnClickListener { selected.clear(); (doc()?.pages?.indices ?: IntRange.EMPTY).forEach { selected.add(it) }; adapter.notifyDataSetChanged(); refreshSelection() }
        b.btnSelectNone.setOnClickListener { selected.clear(); adapter.notifyDataSetChanged(); refreshSelection() }
        b.btnExportSel.setOnClickListener {
            val d2 = doc() ?: return@setOnClickListener
            val paths = selected.sorted().mapNotNull { d2.pages.getOrNull(it) }
            if (paths.isEmpty()) { toast("No pages selected"); return@setOnClickListener }
            exportPdf(paths, (d2.title) + "_selected")
        }
        b.btnDeleteSel.setOnClickListener { deleteSelected() }

        b.btnCopyOcr.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("ocr", b.ocrText.text.toString()))
            toast("Copied")
        }
        b.btnCloseOcr.setOnClickListener { b.ocrPanel.visibility = android.view.View.GONE }
    }

    override fun onResume() {
        super.onResume()
        // Pages appended via camera session
        if (DocRepository.activeSessionPages.isNotEmpty()) {
            doc()?.let { it.pages.addAll(DocRepository.activeSessionPages); DocRepository.save() }
            DocRepository.activeSessionPages.clear()
        }
        DocRepository.editingExistingPage = null
        selected.clear()
        adapter.notifyDataSetChanged()
        refreshSelection()
    }

    private fun refreshSelection() {
        b.selectionToolbar.visibility = if (selected.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        b.selectionCount.text = "${selected.size} selected"
    }

    private fun movePage(pos: Int, dir: Int) {
        val d = doc() ?: return
        val to = pos + dir
        if (to < 0 || to >= d.pages.size) return
        java.util.Collections.swap(d.pages, pos, to)
        DocRepository.save()
        selected.clear()
        adapter.notifyDataSetChanged()
        refreshSelection()
    }

    private fun rotatePage(pos: Int) {
        val d = doc() ?: return
        val path = d.pages.getOrNull(pos) ?: return
        val bmp = BitmapFactory.decodeFile(path) ?: return
        val m = Matrix().apply { postRotate(90f) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        val png = path.endsWith(".png", true)
        val newPath = DocRepository.savePageBitmap(rotated, png)
        runCatching { File(path).delete() }
        d.pages[pos] = newPath
        DocRepository.save()
        adapter.notifyItemChanged(pos)
    }

    private fun deletePage(pos: Int) {
        val d = doc() ?: return
        AlertDialog.Builder(this).setMessage("Delete this page?")
            .setPositiveButton("Delete") { _, _ ->
                d.pages.getOrNull(pos)?.let { runCatching { File(it).delete() } }
                if (pos in d.pages.indices) d.pages.removeAt(pos)
                DocRepository.save()
                selected.clear()
                adapter.notifyDataSetChanged()
                refreshSelection()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun deleteSelected() {
        val d = doc() ?: return
        if (selected.isEmpty()) return
        AlertDialog.Builder(this).setMessage("Delete ${selected.size} page(s)?")
            .setPositiveButton("Delete") { _, _ ->
                selected.sortedDescending().forEach { idx ->
                    d.pages.getOrNull(idx)?.let { runCatching { File(it).delete() } }
                    if (idx in d.pages.indices) d.pages.removeAt(idx)
                }
                DocRepository.save()
                selected.clear()
                adapter.notifyDataSetChanged()
                refreshSelection()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun editPage(pos: Int, crop: Boolean) {
        val d = doc() ?: return
        val path = d.pages.getOrNull(pos) ?: return
        val bmp = BitmapFactory.decodeFile(path) ?: return
        DocRepository.editingExistingPage = pos
        DocRepository.filterViewOrigin = "document"
        if (crop) {
            ScanSession.rawImage = bmp
            startActivity(Intent(this, CropActivity::class.java))
        } else {
            ScanSession.warpedImage = bmp
            startActivity(Intent(this, FilterActivity::class.java))
        }
    }

    private fun importImages(uris: List<Uri>) {
        val d = doc() ?: return
        var added = 0
        uris.forEach { uri ->
            runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    val bmp = BitmapFactory.decodeStream(input)
                    if (bmp != null) { d.pages.add(DocRepository.savePageBitmap(bmp)); added++ }
                }
            }
        }
        DocRepository.save()
        adapter.notifyDataSetChanged()
        toast("Added $added image(s)")
    }

    private fun importPdf(uri: Uri) {
        val d = doc() ?: return
        try {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val renderer = android.graphics.pdf.PdfRenderer(pfd)
                var added = 0
                for (i in 0 until renderer.pageCount) {
                    val page = renderer.openPage(i)
                    val scale = 2
                    val bmp = Bitmap.createBitmap(page.width * scale, page.height * scale, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    d.pages.add(DocRepository.savePageBitmap(bmp))
                    added++
                }
                renderer.close()
                DocRepository.save()
                adapter.notifyDataSetChanged()
                toast("Imported $added page(s)")
            }
        } catch (e: Exception) {
            toast("PDF import failed: ${e.message}")
        }
    }

    private fun runOcr() {
        val d = doc() ?: return
        val target = if (selected.isNotEmpty()) selected.sorted().mapNotNull { d.pages.getOrNull(it) }
        else d.pages
        if (target.isEmpty()) { toast("No pages"); return }
        b.ocrPanel.visibility = android.view.View.VISIBLE
        b.ocrProgress.visibility = android.view.View.VISIBLE
        b.ocrStatus.text = "Recognizing..."
        b.ocrText.setText("")
        lifecycleScope.launch {
            val sb = StringBuilder()
            try {
                target.forEachIndexed { i, path ->
                    b.ocrStatus.text = "Recognizing ${i + 1}/${target.size}..."
                    val text = OcrHelper.recognizeFile(path)
                    if (target.size > 1) sb.append("--- Page ${i + 1} ---\n")
                    sb.append(text).append("\n\n")
                }
                b.ocrProgress.visibility = android.view.View.GONE
                b.ocrStatus.text = "Recognized Text"
                b.ocrText.setText(sb.toString().trim())
            } catch (e: Exception) {
                b.ocrProgress.visibility = android.view.View.GONE
                b.ocrStatus.text = "OCR error"
                b.ocrText.setText(e.message ?: "error")
            }
        }
    }

    private fun exportPdf(paths: List<String>, title: String) {
        if (paths.isEmpty()) { toast("No pages to export"); return }
        try {
            val outFile = PdfExporter.export(this, title, paths)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", outFile)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Share PDF"))
        } catch (e: Exception) {
            toast("Export failed: ${e.message}")
        }
    }

    private fun confirmDeleteDoc() {
        val id = DocRepository.viewingDocId ?: return
        AlertDialog.Builder(this).setMessage("Delete this document?")
            .setPositiveButton("Delete") { _, _ ->
                DocRepository.deleteDocument(id)
                goDashboard()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun goDashboard() {
        startActivity(Intent(this, DashboardActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP })
        finish()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
