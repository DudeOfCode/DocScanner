package com.docscanner.app.data

import android.content.Context
import android.graphics.Bitmap
import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Singleton in-memory + on-disk store for scanned documents.
 * Pages are persisted as JPEG/PNG files under filesDir/docs and the document
 * index is serialized to docs/index.json.
 */
object DocRepository {

    val documents = mutableListOf<ScanDocument>()
    private var idCounter = 0L
    private lateinit var root: File
    private val gson = Gson()

    /** Transient session state used while scanning / editing (mirrors web globals). */
    var viewingDocId: Long? = null
    val activeSessionPages = mutableListOf<String>()
    var editingExistingPage: Int? = null
    var filterViewOrigin: String = "crop" // "crop" | "document"

    fun init(context: Context) {
        if (::root.isInitialized) return
        root = File(context.filesDir, "docs").apply { mkdirs() }
        load()
    }

    fun dir(): File = root

    private fun indexFile() = File(root, "index.json")

    private fun load() {
        val f = indexFile()
        if (!f.exists()) return
        runCatching {
            val store = gson.fromJson(f.readText(), Store::class.java)
            documents.clear()
            documents.addAll(store.documents)
            idCounter = store.idCounter
        }
    }

    fun save() {
        runCatching { indexFile().writeText(gson.toJson(Store(documents, idCounter))) }
    }

    fun nextId(): Long = ++idCounter

    fun findDoc(id: Long?): ScanDocument? = documents.firstOrNull { it.id == id }

    fun now(): String =
        SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date())

    /** Persist a bitmap to a new page file, returns absolute path. */
    fun savePageBitmap(bmp: Bitmap, asPng: Boolean = false): String {
        val ext = if (asPng) "png" else "jpg"
        val file = File(root, "page_${System.currentTimeMillis()}_${(0..9999).random()}.$ext")
        FileOutputStream(file).use { out ->
            if (asPng) bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            else bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        return file.absolutePath
    }

    fun deleteDocument(id: Long) {
        findDoc(id)?.let { doc ->
            doc.pages.forEach { runCatching { File(it).delete() } }
            documents.remove(doc)
        }
        save()
    }

    private data class Store(val documents: List<ScanDocument>, val idCounter: Long)
}
