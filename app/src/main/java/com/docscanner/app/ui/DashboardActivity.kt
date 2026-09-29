package com.docscanner.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.docscanner.app.data.DocRepository
import com.docscanner.app.data.ScanDocument
import com.docscanner.app.databinding.ActivityDashboardBinding
import com.docscanner.app.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import android.graphics.Color
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.AdRequest

class DashboardActivity : AppCompatActivity() {

    private lateinit var b: ActivityDashboardBinding
    private lateinit var adapter: DocumentsAdapter

    private val pickImages = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> if (uris.isNotEmpty()) importImages(uris) }

    private val pickPdf = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> if (uris.isNotEmpty()) importPdfs(uris) }

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startNewScan() else
            Toast.makeText(this, "Camera permission is required. Use Import instead.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DocRepository.init(applicationContext)
        b = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(b.root)
        MobileAds.initialize(this) {}
        b.adView.loadAd(AdRequest.Builder().build())
        adapter = DocumentsAdapter { doc -> openDoc(doc) }
        b.docsGrid.layoutManager = GridLayoutManager(this, 2)
        b.docsGrid.adapter = adapter

        b.btnNewScan.setOnClickListener { ensureCameraThenScan() }
        b.btnImport.setOnClickListener { showImportMenu() }

        maybeRequestNotifications()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        adapter.submit(DocRepository.documents.toList())
        b.emptyState.visibility = if (DocRepository.documents.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun openDoc(doc: ScanDocument) {
        DocRepository.viewingDocId = doc.id
        startActivity(Intent(this, DocumentActivity::class.java))
    }

    private fun ensureCameraThenScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startNewScan()
        else requestCamera.launch(Manifest.permission.CAMERA)
    }

    private fun startNewScan() {
        DocRepository.viewingDocId = null
        DocRepository.activeSessionPages.clear()
        startActivity(Intent(this, CameraActivity::class.java))
    }

    private fun showImportMenu() {
        val opts = arrayOf("Import Images", "Import / Merge PDF")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Import")
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> pickImages.launch("image/*")
                    1 -> pickPdf.launch("application/pdf")
                }
            }.show()
    }

    private fun importImages(uris: List<Uri>) {
        DocRepository.viewingDocId = null
        lifecycleScope.launch {
            val pages = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching {
                        contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
                    }.getOrNull()?.let { bmp ->
                        val enhanced = ImageUtils.autoEnhance(bmp)
                        DocRepository.savePageBitmap(enhanced)
                    }
                }
            }
            finalizeImportedPages(pages)
        }
    }

    private fun importPdfs(uris: List<Uri>) {
        DocRepository.viewingDocId = null
        lifecycleScope.launch {
            val pages = withContext(Dispatchers.IO) {
                val out = mutableListOf<String>()
                for (uri in uris) out += renderPdf(uri)
                out
            }
            finalizeImportedPages(pages)
        }
    }

    private fun renderPdf(uri: Uri): List<String> {
        val result = mutableListOf<String>()
        runCatching {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd: ParcelFileDescriptor ->
                PdfRenderer(pfd).use { renderer ->
                    for (i in 0 until renderer.pageCount) {
                        renderer.openPage(i).use { page ->
                            val scale = 2
                            val bmp = Bitmap.createBitmap(page.width * scale, page.height * scale, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            result += DocRepository.savePageBitmap(bmp)
                        }
                    }
                }
            }
        }
        return result
    }

    private fun finalizeImportedPages(pages: List<String>) {
        if (pages.isEmpty()) { Toast.makeText(this, "Nothing imported.", Toast.LENGTH_SHORT).show(); return }
        val id = DocRepository.nextId()
        val doc = ScanDocument(id, "Imported Doc (${pages.size} pg)", DocRepository.now(), pages.toMutableList())
        DocRepository.documents.add(doc)
        DocRepository.save()
        DocRepository.viewingDocId = id
        refresh()
        startActivity(Intent(this, DocumentActivity::class.java))
    }
     
    override fun onDestroy() {
        b.adsterraView.destroy()
        super.onDestroy()
    }
    
            private fun loadSmartlinkBanner() {
        val wv = b.adsterraView
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.javaScriptCanOpenWindowsAutomatically = true
        s.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // Many ad pages block the default WebView user agent (it contains "; wv")
        s.userAgentString = s.userAgentString.replace("; wv", "")

        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.setBackgroundColor(android.graphics.Color.WHITE)

        wv.webChromeClient = android.webkit.WebChromeClient()
        wv.webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: android.webkit.WebView,
                request: android.webkit.WebResourceRequest
            ): Boolean = request.url.scheme !in listOf("http", "https")

            override fun onReceivedError(
                view: android.webkit.WebView,
                request: android.webkit.WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                if (request.isForMainFrame) {
                    Toast.makeText(
                        this@DashboardActivity,
                        "Ad error: ${error.description}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        wv.loadUrl("https://www.profitableratecpmnetwork.com/q2rvva0sh9?key=820752a224bdea633c6a0b978fc8ff86")
    }
    private fun maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
