package com.aicamerapro

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.aicamerapro.processing.EnhanceMode
import com.aicamerapro.processing.EnhanceSettings
import com.aicamerapro.processing.VividProcessor
import com.aicamerapro.processing.PortraitProcessor
import com.aicamerapro.processing.SceneAnalyzer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var preview: PreviewView
    private lateinit var photo: CompareView
    private lateinit var capture: ImageCapture
    private lateinit var status: TextView
    private lateinit var intensityLabel: TextView
    private lateinit var intensityTitle: TextView
    private lateinit var modeRow: LinearLayout
    private lateinit var blurPresetButton: TextView
    private lateinit var flashButton: TextView
    private var original: Bitmap? = null
    private var enhanced: Bitmap? = null
    private var currentMode = EnhanceMode.AI
    private var intensity = 0.58f
    private var showingOriginal = false
    private var cameraBound = false
    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashEnabled = false
    private var saveMode = "both"
    private var startupMode = "AI"
    private var defaultPortraitBlur = 0.50f
    private var sourceExifUri: Uri? = null
    private var sourceExifFile: File? = null

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCamera() else status.text = "Camera permission is needed to take photos"
    }
    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { loadPhoto(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(9, 11, 24)
        window.navigationBarColor = Color.rgb(9, 11, 24)
        buildInterface()
        lifecycleScope.launch {
            val stored = withContext(Dispatchers.IO) {
                listOf(
                    CameraSettings.readSaveMode(this@MainActivity),
                    CameraSettings.readStartupMode(this@MainActivity),
                    CameraSettings.readPortraitBlur(this@MainActivity).toString(),
                    CameraSettings.readFlashEnabled(this@MainActivity).toString()
                )
            }
            saveMode = stored[0]
            startupMode = stored[1]
            currentMode = runCatching { EnhanceMode.valueOf(startupMode) }.getOrDefault(EnhanceMode.AI)
            defaultPortraitBlur = stored[2].toFloatOrNull()?.coerceIn(0.25f, 1f) ?: 0.50f
            intensity = 0.58f
            flashEnabled = stored[3].toBoolean()
            flashButton.text = if (flashEnabled) "⚡ FLASH ON" else "⚡ FLASH OFF"
            intensityTitle.text = if (currentMode == EnhanceMode.PORTRAIT) "BACKGROUND BLUR" else "VIBRANCE POWER"
            intensityLabel.text = "${(intensity * 100).toInt()}%"
            refreshModes()
            if (cameraProvider != null) bindCamera()
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun gradient(a: Int, b: Int, radius: Int = 24) =
        android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(a, b)).apply { cornerRadius = dp(radius).toFloat() }
    private fun label(text: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false): TextView =
        TextView(this).apply { this.text = text; textSize = size; setTextColor(color); if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD) }

    private fun buildInterface() {
        // Native-camera-inspired layout: edge-to-edge preview with compact floating controls.
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.navigationBarColor = Color.BLACK

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        preview.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                boundCamera?.let { camera ->
                    val point = preview.meteringPointFactory.createPoint(event.x, event.y)
                    val action = FocusMeteringAction.Builder(point)
                        .setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                    camera.cameraControl.startFocusAndMetering(action)
                    status.text = "Focus locked"
                }
                true
            } else true
        }
        photo = CompareView(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
        }
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        root.addView(photo, FrameLayout.LayoutParams(-1, -1))

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x99000000.toInt(), Color.TRANSPARENT)
            )
        }
        fun topControl(text: String, description: String, click: () -> Unit): TextView =
            label(text, 22f, Color.WHITE).apply {
                contentDescription = description
                gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0x33222222)
                }
                setOnClickListener { click() }
            }
        flashButton = topControl(if (flashEnabled) "ϟ" else "ϟ", "Flash") { toggleFlash() }
        topBar.addView(flashButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        val spacer = View(this)
        topBar.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        topBar.addView(topControl("⚙", "Camera settings") { showSaveSettings() },
            LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        status = label("Ready", 11f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0x66000000)
            }
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(68)
        })

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(18), dp(14), dp(22))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xEE000000.toInt(), 0xAA000000.toInt(), Color.TRANSPARENT)
            )
        }

        modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        addMode("AI", EnhanceMode.AI, Color.WHITE)
        addMode("VIVID", EnhanceMode.VIVID, Color.WHITE)
        addMode("PORTRAIT", EnhanceMode.PORTRAIT, Color.WHITE)
        addMode("NORMAL", EnhanceMode.NORMAL, Color.WHITE)
        bottom.addView(modeRow, LinearLayout.LayoutParams(-1, dp(42)))
        
        val captureRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        fun roundButton(symbol: String, labelText: String, size: Int, fill: Int, click: () -> Unit): TextView =
            label(symbol, 25f, Color.WHITE).apply {
                contentDescription = labelText
                gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(fill)
                    setStroke(dp(1), 0x99FFFFFF.toInt())
                }
                setOnClickListener { click() }
            }

        captureRow.addView(roundButton("▧", "Open gallery", 52, 0x55333333) {
            picker.launch("image/*")
        }, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(38) })

        val shutter = roundButton("", "Take photo", 72, Color.WHITE) { takePhoto() }.apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(4), 0xFFBDBDBD.toInt())
            }
        }
        captureRow.addView(shutter, LinearLayout.LayoutParams(dp(72), dp(72)))

        captureRow.addView(roundButton("⟳", "Switch camera", 52, 0x55333333) {
            switchCamera()
        }, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginStart = dp(38) })
        bottom.addView(captureRow)

        blurPresetButton = label("Portrait blur settings", 12f, Color.WHITE).apply {
            visibility = View.GONE
            setOnClickListener { showBlurPresets() }
        }
        bottom.addView(blurPresetButton)
        intensityTitle = label("AI ENHANCEMENT", 10f, Color.WHITE, true)
        intensityLabel = label("58%", 10f, Color.WHITE, true)
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        refreshModes()
    }

    private fun space(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun action(text: String, color: Int, click: () -> Unit) =
        label(text, 12f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = gradient(color, color, 16)
            setOnClickListener { click() }
        }

    private fun addMode(title: String, mode: EnhanceMode, color: Int) {
        val view = label(title, 11f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener {
                currentMode = mode
                intensityTitle.text = if (mode == EnhanceMode.PORTRAIT) "BACKGROUND BLUR" else "AI ENHANCEMENT"
                showingOriginal = false
                refreshModes()
                original?.let { process(it) }
            }
        }
        modeRow.addView(view, LinearLayout.LayoutParams(0, dp(40), 1f))
    }

    private fun refreshModes() {
        for (i in 0 until modeRow.childCount) {
            val selected = (i == 0 && currentMode == EnhanceMode.AI) ||
                (i == 1 && currentMode == EnhanceMode.VIVID) ||
                (i == 2 && currentMode == EnhanceMode.PORTRAIT) ||
                (i == 3 && currentMode == EnhanceMode.NORMAL)
            val item = modeRow.getChildAt(i) as TextView
            item.alpha = if (selected) 1f else 0.55f
            item.setTextColor(if (selected) Color.WHITE else 0xFFBBBBBB.toInt())
            item.paint.isFakeBoldText = selected
            item.background = if (selected) android.graphics.drawable.GradientDrawable().apply {
                setColor(0x33222222)
                cornerRadius = dp(18).toFloat()
            } else android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        }
        if (::blurPresetButton.isInitialized) {
            blurPresetButton.visibility = if (currentMode == EnhanceMode.PORTRAIT) View.VISIBLE else View.GONE
        }
    }

    private fun buildCameraAdjustments(root: LinearLayout) {
        fun adjustmentRow(title: String, initial: Int, onChange: (Int) -> Unit) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(label(title,9f,0xFFBFB7DC.toInt(),true), LinearLayout.LayoutParams(dp(82),-2))
            val seek = SeekBar(this).apply {
                max = 100; progress = initial
                progressTintList = android.content.res.ColorStateList.valueOf(0xFF00E5FF.toInt())
                thumbTintList = android.content.res.ColorStateList.valueOf(0xFFFF4FA3.toInt())
                setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            row.addView(seek, LinearLayout.LayoutParams(0,dp(32),1f))
            root.addView(row)
        }
        adjustmentRow("EXPOSURE",50) { value ->
            val camera = boundCamera ?: return@adjustmentRow
            val range = camera.cameraInfo.exposureState.exposureCompensationRange
            val index = range.lower + ((range.upper - range.lower) * value / 100f).toInt()
            camera.cameraControl.setExposureCompensationIndex(index)
            status.text = "Exposure compensation: $index"
        }
        adjustmentRow("ZOOM",0) { value ->
            val camera = boundCamera ?: return@adjustmentRow
            val maxZoom = camera.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
            val ratio = 1f + (maxZoom - 1f) * value / 100f
            camera.cameraControl.setZoomRatio(ratio)
            status.text = "Camera zoom: %.1fx".format(Locale.US, ratio)
        }
        root.addView(space(4))
    }

    private fun syncCameraControls() {
        boundCamera?.cameraControl?.setExposureCompensationIndex(0)
        boundCamera?.cameraControl?.setZoomRatio(1f)
    }

    private fun showBlurPresets() {
        val names = arrayOf("Natural  •  25%", "Medium  •  50%", "Strong  •  75%", "Maximum  •  100%")
        val values = floatArrayOf(0.25f, 0.50f, 0.75f, 1.0f)
        AlertDialog.Builder(this)
            .setTitle("Portrait background blur")
            .setItems(names) { dialog, which ->
                currentMode = EnhanceMode.PORTRAIT
                intensity = values[which]
                intensityTitle.text = "BACKGROUND BLUR"
                intensityLabel.text = "${(intensity * 100).toInt()}%"
                refreshModes()
                original?.let { process(it) }
                dialog.dismiss()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun startCamera() {
        val future=ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCamera()
        },ContextCompat.getMainExecutor(this))
    }
    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val cameraPreview = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
        capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(if (flashEnabled && lensFacing == CameraSelector.LENS_FACING_BACK) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
            .build()
        try {
            provider.unbindAll()
            boundCamera = provider.bindToLifecycle(this, selector, cameraPreview, capture)
            cameraBound = true
            syncCameraControls()
            status.text = if (lensFacing == CameraSelector.LENS_FACING_BACK) "Rear camera ready • AI mode" else "Front camera ready • AI mode"
        } catch (e: Exception) {
            cameraBound = false
            status.text = "Camera unavailable: " + e.localizedMessage
        }
    }

    private fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        if (cameraProvider != null) bindCamera()
    }

    private fun toggleFlash() {
        flashEnabled = !flashEnabled
        flashButton.text = if (flashEnabled) "⚡ FLASH ON" else "⚡ FLASH OFF"
        lifecycleScope.launch { withContext(Dispatchers.IO) { CameraSettings.writeFlashEnabled(this@MainActivity, flashEnabled) } }
        if (cameraProvider != null && lensFacing == CameraSelector.LENS_FACING_BACK) bindCamera()
        status.text = if (flashEnabled) "Flash enabled for rear camera" else "Flash disabled"
    }

    private fun takePhoto() {
        if(!cameraBound) { status.text="Camera is starting…"; return }
        status.text="Capturing full-resolution photo…"
        val file=File(cacheDir,"capture_"+System.currentTimeMillis()+".jpg")
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),ContextCompat.getMainExecutor(this),object: ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                worker.execute {
                    val bitmap = decodeUpright(open = { file.inputStream() }, exifOrientation = readExifOrientation { ExifInterface(file.absolutePath) })
                    runOnUiThread {
                        if (bitmap != null) { sourceExifFile = file; sourceExifUri = null; showPhoto(bitmap) } else status.text = "Could not read captured image"
                    }
                }
            }
            override fun onError(exception: ImageCaptureException) { status.text="Capture failed: "+exception.message }
        })
    }
    /** Reads the EXIF orientation tag; returns NORMAL if it cannot be read. */
    private fun readExifOrientation(factory: () -> ExifInterface?): Int = try {
        factory()?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ?: ExifInterface.ORIENTATION_NORMAL
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /**
     * Decodes a JPEG, down-sampling only extremely large images (over ~20 MP) so the
     * app does not run out of memory, then rotates it upright using the EXIF tag.
     */
    private fun decodeUpright(open: () -> java.io.InputStream?, exifOrientation: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            val maxPixels = 20_000_000L
            while (bounds.outWidth.toLong() * bounds.outHeight / (sample.toLong() * sample) > maxPixels) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = open()?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
            rotateByExif(decoded, exifOrientation)
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun rotateByExif(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = android.graphics.Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            rotated
        } catch (_: OutOfMemoryError) {
            bitmap
        }
    }

    private fun loadPhoto(uri: Uri) {
        worker.execute {
            val orientation = readExifOrientation { contentResolver.openInputStream(uri)?.use { ExifInterface(it) } }
            val bitmap = decodeUpright(open = { contentResolver.openInputStream(uri) }, exifOrientation = orientation)
            runOnUiThread { if(bitmap!=null) { sourceExifUri = uri; sourceExifFile = null; showPhoto(bitmap) } else status.text="Could not open this photo" }
        }
    }
    private fun showPhoto(bitmap: Bitmap) { original=bitmap; enhanced=null; photo.setImages(bitmap, null); photo.visibility=View.VISIBLE; preview.visibility=View.GONE; showingOriginal=false; process(bitmap) }
    private fun process(bitmap: Bitmap) {
        status.text="Enhancing colors • "+currentMode.name+"…"
        val settings=EnhanceSettings(mode=currentMode,intensity=intensity,warmth=0.01f,contrast=1.06f)
        if (currentMode == EnhanceMode.PORTRAIT) {
            status.text = "AI subject detection • Portrait blur…"
            PortraitProcessor.enhance(bitmap, settings, intensity,
                { result ->
                    runOnUiThread {
                        enhanced = result
                        photo.setImages(original, result)
                        showingOriginal = false
                        status.text = "✦ Portrait enhanced • Offline AI segmentation"
                    }
                },
                { error ->
                    runOnUiThread {
                        status.text = "Portrait model unavailable • Using safe AI enhancement"
                        worker.execute {
                            val fallback = VividProcessor.enhance(bitmap, settings.copy(mode = EnhanceMode.AI))
                            runOnUiThread { enhanced = fallback; photo.setImages(original, fallback) }
                        }
                    }
                }
            )
        } else if (currentMode == EnhanceMode.AI) {
            status.text = "Offline AI • Analyzing scene…"
            SceneAnalyzer.analyze(bitmap,
                { profile ->
                    worker.execute {
                        val result = VividProcessor.enhance(bitmap, SceneAnalyzer.tune(settings, profile))
                        runOnUiThread {
                            enhanced = result
                            photo.setImages(original, result)
                            showingOriginal = false
                            status.text = "✦ AI scene: " + profile.name.lowercase().replaceFirstChar { it.uppercase() } + " • Adaptive enhancement"
                        }
                    }
                },
                {
                    worker.execute {
                        val result = VividProcessor.enhance(bitmap, settings)
                        runOnUiThread { enhanced = result; photo.setImages(original, result); showingOriginal = false; status.text = "✦ AI fallback enhancement • Offline" }
                    }
                }
            )
        } else {
            worker.execute {
                val result=VividProcessor.enhance(bitmap,settings)
                runOnUiThread { enhanced=result; photo.setImages(original, result); showingOriginal=false; status.text="✦ Enhanced • "+result.width+" × "+result.height+" • On-device" }
            }
        }
    }
    private fun showSaveSettings() {
        val choices = arrayOf("Image saving preference", "Default startup mode", "Default portrait blur", "Flash on launch", "Exposure & zoom")
        AlertDialog.Builder(this).setTitle("Camera settings")
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> showSaveModeSetting()
                    1 -> showStartupModeSetting()
                    2 -> showDefaultBlurSetting()
                    3 -> showFlashPreferenceSetting()
                    4 -> showCameraAdjustments()
                }
            }.setNegativeButton("CLOSE", null).show()
    }

    private fun showCameraAdjustments() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        buildCameraAdjustments(panel)
        AlertDialog.Builder(this)
            .setTitle("Camera adjustments")
            .setView(panel)
            .setPositiveButton("DONE", null)
            .show()
    }

    private fun showSaveModeSetting() {
        val choices = arrayOf("Original + Enhanced", "Enhanced Only")
        AlertDialog.Builder(this).setTitle("Image saving preference")
            .setSingleChoiceItems(choices, if (saveMode == "both") 0 else 1) { dialog, which ->
                val selected = if (which == 0) "both" else "enhanced"
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { CameraSettings.writeSaveMode(this@MainActivity, selected) }
                    saveMode = selected
                    status.text = "Save preference: " + choices[which]
                }
                dialog.dismiss()
            }.setNegativeButton("CANCEL", null).show()
    }

    private fun showStartupModeSetting() {
        val modes = arrayOf("AI", "VIVID", "NORMAL", "PORTRAIT")
        AlertDialog.Builder(this).setTitle("Default startup mode")
            .setSingleChoiceItems(modes, modes.indexOf(startupMode).coerceAtLeast(0)) { dialog, which ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { CameraSettings.writeStartupMode(this@MainActivity, modes[which]) }
                    startupMode = modes[which]
                    status.text = "Startup mode saved: " + modes[which]
                }
                dialog.dismiss()
            }.setNegativeButton("CANCEL", null).show()
    }

    private fun showDefaultBlurSetting() {
        val labels = arrayOf("Natural", "Medium", "Strong", "Maximum")
        val values = floatArrayOf(0.25f, 0.50f, 0.75f, 1f)
        val selected = values.indices.minByOrNull { kotlin.math.abs(values[it] - defaultPortraitBlur) } ?: 1
        AlertDialog.Builder(this).setTitle("Default portrait blur")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { CameraSettings.writePortraitBlur(this@MainActivity, values[which]) }
                    defaultPortraitBlur = values[which]
                    intensity = values[which]
                    intensityLabel.text = "${(intensity * 100).toInt()}%"
                    status.text = "Portrait blur preference saved"
                }
                dialog.dismiss()
            }.setNegativeButton("CANCEL", null).show()
    }

    private fun showFlashPreferenceSetting() {
        val choices = arrayOf("Flash off", "Flash on")
        AlertDialog.Builder(this).setTitle("Flash on launch")
            .setSingleChoiceItems(choices, if (flashEnabled) 1 else 0) { dialog, which ->
                flashEnabled = which == 1
                flashButton.text = if (flashEnabled) "⚡ FLASH ON" else "⚡ FLASH OFF"
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { CameraSettings.writeFlashEnabled(this@MainActivity, flashEnabled) }
                    if (cameraProvider != null) bindCamera()
                    status.text = "Flash preference saved"
                }
                dialog.dismiss()
            }.setNegativeButton("CANCEL", null).show()
    }

    private fun copySourceExifTo(target: File) {
        val source = try {
            sourceExifFile?.takeIf { it.exists() }?.let { ExifInterface(it.absolutePath) }
                ?: sourceExifUri?.let { uri -> contentResolver.openInputStream(uri)?.use { ExifInterface(it) } }
        } catch (_: Exception) { null } ?: return
        val output = ExifInterface(target.absolutePath)
        val tags = arrayOf(
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
            ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH
        )
        tags.forEach { tag -> source.getAttribute(tag)?.let { output.setAttribute(tag, it) } }
        output.saveAttributes()
    }

    private fun writeBitmapToGallery(bitmap: Bitmap, name: String): Boolean {
        val temp = File(cacheDir, name)
        try {
            temp.outputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 96, output)) return false
            }
            copySourceExifTo(temp)
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AI Camera Pro")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            val ok = try {
                contentResolver.openOutputStream(uri)?.use { output -> temp.inputStream().use { it.copyTo(output) }; true } == true
            } catch (_: Exception) { false }
            if (!ok) contentResolver.delete(uri, null, null)
            return ok
        } catch (_: Exception) {
            return false
        } finally {
            temp.delete()
        }
    }

    private fun savePhoto() {
        val bitmap = enhanced ?: run { status.text = "Capture or open a photo first"; return }
        val originalBitmap = original
        val selectedMode = saveMode
        status.text = "Saving photo…"
        worker.execute {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val enhancedOk = writeBitmapToGallery(bitmap, "AICameraPro_${stamp}_Enhanced.jpg")
            val originalOk = if (selectedMode == "both" && originalBitmap != null) {
                writeBitmapToGallery(originalBitmap, "AICameraPro_${stamp}_Original.jpg")
            } else true
            runOnUiThread {
                status.text = when {
                    enhancedOk && originalOk && selectedMode == "both" -> "Original + Enhanced saved • Pictures / AI Camera Pro"
                    enhancedOk && originalOk -> "Enhanced photo saved • Pictures / AI Camera Pro"
                    else -> "Could not save all selected photos"
                }
            }
        }
    }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
