package com.docscanner.app.util

import android.graphics.Bitmap

/**
 * Transient holder to pass large bitmaps between activities without going
 * through the (size-limited) Intent bundle.
 */
object ScanSession {
    /** The raw captured/loaded image being edited in the crop step. */
    var rawImage: Bitmap? = null
    /** The perspective-corrected image handed to the filter step. */
    var warpedImage: Bitmap? = null
}
