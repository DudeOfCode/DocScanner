package com.docscanner.app.data

/**
 * A scanned document consisting of an ordered list of page image file paths.
 * Mirrors the `globalDocuments` structure from the original web app.
 */
data class ScanDocument(
    var id: Long,
    var title: String,
    var date: String,
    val pages: MutableList<String> = mutableListOf()
)
