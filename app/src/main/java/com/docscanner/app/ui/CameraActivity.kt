package com.docscanner.app.ui

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.util.Range
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.docscanner.app.data.DocRepository
import com.docscanner.app.databinding.ActivityCameraBinding
import com.docscanner.app.databinding.ViewManualControlsBinding
import com.docscanner.app.util.ScanSession
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalCamera2Interop::class)
class CameraActivity : AppCompatActivity() {

    private lateinit var b: ActivityCameraBinding
    private lateinit var mc: ViewManualControlsBinding

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var manualMode = false

    // hardware ranges
    private var isoRange: Range<Int>? = null
    private var exposureRange: Range<Long>? = null
    private var minFocus = 0f // dioptres; 0 = infinity

    private val pickImages = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> if (uris.isNotEmpty()) importImages(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(b.root)
        mc = ViewManualControlsBinding.bind(b.manualPanel.root)

        b.btnBack.setOnClickListener { finish() }
        b.btnFlash.setOnClickListener { cycleFlash() }
        b.btnGrid.setOnClickListener {
            b.gridOverlay.visibility = if (b.gridOverlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        b.btnSwitch.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            bindCamera()
        }
        b.btnTune.setOnClickListener {
            mc.root.visibility = if (mc.root.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        b.btnShutter.setOnClickListener { capture() }
        b.btnImportImages.setOnClickListener { pickImages.launch("image/*") }
        b.btnFinish.setOnClickListener { finishSession() }

        setupManualControls()
        setupTapToFocus()
        updateSessionUi()
        startCamera()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            provider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = provider ?: return
        provider.unbindAll()
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(b.previewView.surfaceProvider)
        }
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(flashMode)
            .build()
        try {
            camera = provider.bindToLifecycle(this, selector, preview, imageCapture)
            readCharacteristics()
            applyExposureCompensation(mc.seekEv.progress)
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open camera: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun readCharacteristics() {
        val cam = camera ?: return
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        isoRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        exposureRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        minFocus = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

        // EV range from CameraX
        val evState = cam.cameraInfo.exposureState
        if (evState.isExposureCompensationSupported) {
            val r = evState.exposureCompensationRange
            mc.seekEv.max = r.upper - r.lower
            mc.seekEv.progress = -r.lower // 0 EV
        } else {
            mc.seekEv.isEnabled = false
        }

        val maxZoom = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
        mc.seekZoom.max = ((maxZoom - 1f) * 10).roundToInt().coerceAtLeast(1)
    }

    private fun setupManualControls() {
        // White balance options
        val wbNames = listOf("Auto", "Incandescent", "Fluorescent", "Daylight", "Cloudy", "Shade")
        mc.spinnerWb.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, wbNames)

        mc.switchManual.setOnCheckedChangeListener { _, checked ->
            manualMode = checked
            applyManual()
        }

        mc.seekEv.setOnSeekBarChangeListener(simple { applyExposureCompensation(it) })
        mc.seekIso.max = 100
        mc.seekIso.setOnSeekBarChangeListener(simple { updateLabels(); if (manualMode) applyManual() })
        mc.seekShutter.max = 100
        mc.seekShutter.setOnSeekBarChangeListener(simple { updateLabels(); if (manualMode) applyManual() })
        mc.seekFocus.max = 100
        mc.seekFocus.setOnSeekBarChangeListener(simple { updateLabels(); if (manualMode) applyManual() })
        mc.seekZoom.setOnSeekBarChangeListener(simple { progress ->
            val ratio = 1f + progress / 10f
            camera?.cameraControl?.setZoomRatio(ratio)
            mc.lblZoom.text = "Zoom: %.1fx".format(ratio)
        })
        mc.spinnerWb.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) { applyManual() }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        updateLabels()
    }

    private fun simple(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) = onChange(progress)
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    private fun isoValue(): Int {
        val r = isoRange ?: return 100
        return (r.lower + (r.upper - r.lower) * mc.seekIso.progress / 100.0).roundToInt()
    }

    private fun exposureValue(): Long {
        val r = exposureRange ?: return 1_000_000L
        // logarithmic-ish mapping across the supported range
        val frac = mc.seekShutter.progress / 100.0
        return (r.lower + (r.upper - r.lower) * frac).toLong()
    }

    private fun focusValue(): Float = minFocus * (mc.seekFocus.progress / 100f)

    private fun updateLabels() {
        mc.lblEv.text = "Exposure (EV): " + evLabel()
        mc.lblIso.text = if (manualMode) "ISO: ${isoValue()}" else "ISO: Auto"
        mc.lblShutter.text = if (manualMode) "Shutter: 1/${(1_000_000_000.0 / exposureValue()).roundToInt()} s" else "Shutter: Auto"
        mc.lblFocus.text = if (manualMode) (if (mc.seekFocus.progress == 0) "Focus: \u221E" else "Focus: near ${mc.seekFocus.progress}%") else "Focus: Auto"
    }

    private fun evLabel(): String {
        val st = camera?.cameraInfo?.exposureState ?: return "0"
        if (!st.isExposureCompensationSupported) return "n/a"
        val index = st.exposureCompensationRange.lower + mc.seekEv.progress
        val step = st.exposureCompensationStep.toDouble()
        return "%.1f".format(index * step)
    }

    private fun applyExposureCompensation(progress: Int) {
        val st = camera?.cameraInfo?.exposureState ?: return
        if (!st.isExposureCompensationSupported) return
        val index = st.exposureCompensationRange.lower + progress
        camera?.cameraControl?.setExposureCompensationIndex(index)
        updateLabels()
    }

    private fun applyManual() {
        val cam = camera ?: return
        val control = Camera2CameraControl.from(cam.cameraControl)
        val builder = CaptureRequestOptions.Builder()
        if (manualMode) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, isoValue())
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureValue())
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusValue())
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, wbMode())
        } else {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, wbMode())
        }
        control.captureRequestOptions = builder.build()
        updateLabels()
    }

    private fun wbMode(): Int = when (mc.spinnerWb.selectedItemPosition) {
        1 -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
        2 -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
        3 -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
        4 -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
        5 -> CaptureRequest.CONTROL_AWB_MODE_SHADE
        else -> CaptureRequest.CONTROL_AWB_MODE_AUTO
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTapToFocus() {
        b.previewView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val factory = b.previewView.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point).build()
                camera?.cameraControl?.startFocusAndMetering(action)
                showFocusRing(event.x, event.y)
            }
            true
        }
    }

    private fun showFocusRing(x: Float, y: Float) {
        b.focusRing.apply {
            translationX = x - width / 2f
            translationY = y - height / 2f
            visibility = View.VISIBLE
            alpha = 1f
            animate().alpha(0f).setStartDelay(600).setDuration(300)
                .withEndAction { visibility = View.GONE }.start()
        }
    }

    private fun cycleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }
        imageCapture?.flashMode = flashMode
        val label = when (flashMode) {
            ImageCapture.FLASH_MODE_ON -> "Flash: On"
            ImageCapture.FLASH_MODE_AUTO -> "Flash: Auto"
            else -> "Flash: Off"
        }
        b.btnFlash.alpha = if (flashMode == ImageCapture.FLASH_MODE_OFF) 0.5f else 1f
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
    }

    private fun capture() {
        val ic = imageCapture ?: return
        playShutter()
        val file = File(cacheDir, "cap_${System.currentTimeMillis()}.jpg")
        val opts = ImageCapture.OutputFileOptions.Builder(file).build()
        ic.takePicture(opts, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                if (bmp == null) { Toast.makeText(this@CameraActivity, "Capture failed", Toast.LENGTH_SHORT).show(); return }
                ScanSession.rawImage = bmp
                DocRepository.editingExistingPage = null
                DocRepository.filterViewOrigin = "crop"
                startActivity(Intent(this@CameraActivity, CropActivity::class.java))
            }
            override fun onError(exc: ImageCaptureException) {
                Toast.makeText(this@CameraActivity, "Capture error: ${exc.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun playShutter() {
        runCatching {
            ToneGenerator(AudioManager.STREAM_MUSIC, 80).apply {
                startTone(ToneGenerator.TONE_PROP_BEEP, 90)
            }
        }
        b.flashOverlay.alpha = 0.8f
        ObjectAnimator.ofFloat(b.flashOverlay, "alpha", 0.8f, 0f).setDuration(350).start()
    }

    private fun importImages(uris: List<Uri>) {
        // reuse dashboard-style import but add straight to session then finish
        val pages = uris.mapNotNull { uri ->
            runCatching { contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) } }
                .getOrNull()?.let { com.docscanner.app.util.ImageUtils.autoEnhance(it) }
                ?.let { DocRepository.savePageBitmap(it) }
        }
        DocRepository.activeSessionPages.addAll(pages)
        updateSessionUi()
        finishSession()
    }

    private fun updateSessionUi() {
        val n = DocRepository.activeSessionPages.size
        if (n > 0) {
            b.pageCounter.visibility = View.VISIBLE
            b.pageCounter.text = "$n New Page${if (n > 1) "s" else ""}"
            b.btnFinish.alpha = 1f
            b.btnFinish.isClickable = true
        } else {
            b.pageCounter.visibility = View.GONE
            b.btnFinish.alpha = 0.4f
        }
    }

    private fun finishSession() {
        if (DocRepository.activeSessionPages.isEmpty()) { finish(); return }
        val existing = DocRepository.findDoc(DocRepository.viewingDocId)
        if (existing != null) {
            existing.pages.addAll(DocRepository.activeSessionPages)
        } else {
            val id = DocRepository.nextId()
            val doc = com.docscanner.app.data.ScanDocument(
                id, "Scanned Doc (${DocRepository.activeSessionPages.size} pg)",
                DocRepository.now(), DocRepository.activeSessionPages.toMutableList()
            )
            DocRepository.documents.add(doc)
            DocRepository.viewingDocId = id
        }
        DocRepository.activeSessionPages.clear()
        DocRepository.save()
        startActivity(Intent(this, DocumentActivity::class.java))
        finish()
    }

    override fun onResume() {
        super.onResume()
        updateSessionUi()
    }
}
