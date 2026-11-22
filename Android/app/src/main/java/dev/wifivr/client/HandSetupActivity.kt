package dev.wifivr.client

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.content.pm.ActivityInfo
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.MirrorMode
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Range
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class HandSetupActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var overlay: HandOverlayView
    private lateinit var status: TextView
    private lateinit var cameraSwitchButton: Button
    private var useFrontCamera = false
    private lateinit var cameraExecutor: ExecutorService
    private var handLandmarker: HandLandmarker? = null
    private var lastFrameTimestamp = 0L
    private var lastUiUpdateMs = 0L
    private var framesInWindow = 0
    private var fpsWindowStartMs = 0L
    private var headsetMode = false

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else status.text = getString(R.string.camera_permission_needed)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(11, 16, 32)
        window.navigationBarColor = Color.rgb(11, 16, 32)
        cameraExecutor = Executors.newSingleThreadExecutor()
        buildScreen()
        try {
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(2)
                // Lower thresholds help reacquire hands after fast movement or brief occlusion.
                .setMinHandDetectionConfidence(0.35f)
                .setMinHandPresenceConfidence(0.35f)
                .setMinTrackingConfidence(0.35f)
                .setResultListener { result, _ ->
                    val hands = result.landmarks().map { hand -> hand.map { it.x() to it.y() } }
                    val wrist = result.landmarks().firstOrNull()?.firstOrNull()
                    val nowMs = SystemClock.uptimeMillis()
                    if (fpsWindowStartMs == 0L) fpsWindowStartMs = nowMs
                    framesInWindow++
                    val fps = if (nowMs - fpsWindowStartMs >= 1000L) {
                        val measured = framesInWindow * 1000f / (nowMs - fpsWindowStartMs).coerceAtLeast(1L)
                        framesInWindow = 0
                        fpsWindowStartMs = nowMs
                        measured
                    } else null
                    runOnUiThread {
                        overlay.updateHands(hands)
                        if (nowMs - lastUiUpdateMs < 120L && fps == null) return@runOnUiThread
                        lastUiUpdateMs = nowMs
                        val fpsLabel = fps?.let { getString(R.string.tracking_fps, it) } ?: ""
                        status.text = if (hands.isEmpty()) {
                            getString(R.string.hands_not_found) + if (fpsLabel.isNotEmpty()) "\n$fpsLabel" else ""
                        } else {
                            val coordinates = wrist?.let { getString(R.string.wrist_coordinates, it.x(), it.y(), it.z()) }
                                ?: getString(R.string.coordinates_unavailable)
                            getString(R.string.hands_found, hands.size, coordinates) + if (fpsLabel.isNotEmpty()) "\n$fpsLabel" else ""
                        }
                    }
                }
                .setErrorListener { error ->
                    runOnUiThread { status.text = getString(R.string.hand_tracking_error, error.message ?: "") }
                }
                .build()
            handLandmarker = HandLandmarker.createFromOptions(this, options)
        } catch (error: Exception) {
            status.text = getString(R.string.model_load_error, error.message ?: "")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else permissionRequest.launch(Manifest.permission.CAMERA)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun buildScreen() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(11, 16, 32)) }
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        overlay = HandOverlayView(this)
        val infoPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable().apply {
                setColor(0xE6192740.toInt()); cornerRadius = dp(20).toFloat(); setStroke(dp(1), 0x553D597A)
            }
            elevation = dp(8).toFloat()
        }
        val heading = TextView(this).apply {
            text = getString(R.string.hand_tracking_title); textSize = 17f
            typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        }
        status = TextView(this).apply {
            text = getString(R.string.camera_starting); textSize = 13f
            setTextColor(Color.rgb(184, 204, 226)); setPadding(0, dp(5), 0, 0)
        }
        val modeButton = Button(this).apply {
            text = getString(R.string.switch_to_headset_mode); isAllCaps = false
            setOnClickListener {
                headsetMode = !headsetMode
                requestedOrientation = if (headsetMode) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                window.decorView.systemUiVisibility = if (headsetMode)
                    View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                else View.SYSTEM_UI_FLAG_VISIBLE
                text = getString(if (headsetMode) R.string.switch_to_phone_mode else R.string.switch_to_headset_mode)
                this@HandSetupActivity.overlay.setStereoMode(headsetMode)
                previewView.visibility = if (headsetMode) View.GONE else View.VISIBLE
                (infoPanel.layoutParams as FrameLayout.LayoutParams).apply {
                    topMargin = if (headsetMode) dp(10) else statusBarHeight() + dp(12)
                }.also { infoPanel.layoutParams = it }
                status.text = getString(if (headsetMode) R.string.headset_mode_note else R.string.phone_mode_note)
            }
        }
        cameraSwitchButton = Button(this).apply {
            text = getString(R.string.switch_to_front_camera); isAllCaps = false
            setOnClickListener { useFrontCamera = !useFrontCamera; this@HandSetupActivity.overlay.updateHands(emptyList()); startCamera() }
        }
        infoPanel.addView(heading); infoPanel.addView(status)
        infoPanel.addView(cameraSwitchButton); infoPanel.addView(modeButton)
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        root.addView(infoPanel, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            setMargins(dp(16), statusBarHeight() + dp(12), dp(16), 0)
        })
        setContentView(root)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            // Keep a sharper 720p preview while MediaPipe processes a lighter 16:9 frame.
            // Explicitly disable front-camera mirroring so the preview matches real-world coordinates.
            val previewBuilder = Preview.Builder()
                .setTargetResolution(Size(1280, 720))
                .setMirrorMode(MirrorMode.MIRROR_MODE_OFF)
            val preview = previewBuilder.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val analysisBuilder = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 360))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // Ask the camera for a real 60 FPS AE range when the sensor advertises one.
            // Unsupported devices keep their native range rather than failing camera startup.
            try {
                val ranges = Camera2CameraInfo.from(provider.getCameraInfo(selector))
                    .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                val sixty = ranges?.firstOrNull { it.lower == 60 && it.upper == 60 }
                    ?: ranges?.filter { it.upper == 60 && it.lower <= 60 }?.minByOrNull { it.lower }
                if (sixty != null) {
                    Camera2Interop.Extender(previewBuilder)
                        .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, sixty)
                    Camera2Interop.Extender(analysisBuilder)
                        .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, sixty)
                }
            } catch (_: Exception) { }
            val analysis = analysisBuilder.build()
            analysis.setAnalyzer(cameraExecutor) { image -> analyzeFrame(image) }
            try {
                if (!provider.hasCamera(selector)) {
                    useFrontCamera = false
                    cameraSwitchButton.text = getString(R.string.switch_to_front_camera)
                    status.text = getString(R.string.front_camera_unavailable)
                    return@addListener
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, analysis)
                cameraSwitchButton.text = getString(if (useFrontCamera) R.string.switch_to_back_camera else R.string.switch_to_front_camera)
                status.text = getString(if (useFrontCamera) R.string.front_camera_active else R.string.back_camera_active)
            } catch (error: Exception) {
                status.text = getString(R.string.camera_start_error, error.message ?: "")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(image: androidx.camera.core.ImageProxy) {
        try {
            val bitmap = image.toBitmap()
            val matrix = android.graphics.Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
            val rotated = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            // Keep the sensor image unmirrored in both phone and headset modes.
            // Hand landmarks are generated from this exact same bitmap, so they stay aligned.
            val modelBitmap = rotated
            overlay.updateFrame(modelBitmap)
            val mpImage = BitmapImageBuilder(modelBitmap).build()
            val timestamp = maxOf(SystemClock.uptimeMillis(), lastFrameTimestamp + 1)
            lastFrameTimestamp = timestamp
            handLandmarker?.detectAsync(mpImage, timestamp)
        } catch (error: Exception) {
            runOnUiThread { status.text = getString(R.string.camera_frame_error, error.message ?: "") }
        } finally { image.close() }
    }

    override fun onDestroy() {
        handLandmarker?.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}


private class HandOverlayView(context: android.content.Context) : View(context) {
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(73, 226, 178); strokeWidth = 5f; style = Paint.Style.STROKE
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = 3f }
    @Volatile private var handPoints: List<List<Pair<Float, Float>>> = emptyList()
    @Volatile private var cameraFrame: Bitmap? = null
    @Volatile private var stereoMode = false

    fun updateFrame(bitmap: Bitmap) { cameraFrame = bitmap; postInvalidate() }
    fun updateHands(value: List<List<Pair<Float, Float>>>) { handPoints = value; postInvalidate() }
    fun setStereoMode(enabled: Boolean) { stereoMode = enabled; postInvalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val links = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 0 to 5, 5 to 6, 6 to 7, 7 to 8,
            5 to 9, 9 to 10, 10 to 11, 11 to 12, 9 to 13, 13 to 14, 14 to 15, 15 to 16,
            13 to 17, 17 to 18, 18 to 19, 19 to 20, 0 to 17)
        val frame = cameraFrame
        if (stereoMode && frame != null && !frame.isRecycled) {
            val half = width / 2f
            drawEye(canvas, frame, RectF(0f, 0f, half, height.toFloat()), links)
            drawEye(canvas, frame, RectF(half, 0f, width.toFloat(), height.toFloat()), links)
            canvas.drawLine(half, 0f, half, height.toFloat(), dividerPaint)
        } else {
            drawHands(canvas, RectF(0f, 0f, width.toFloat(), height.toFloat()), null, links)
        }
    }

    private fun drawEye(canvas: Canvas, frame: Bitmap, destination: RectF, links: List<Pair<Int, Int>>) {
        val targetAspect = destination.width() / destination.height().coerceAtLeast(1f)
        val sourceAspect = frame.width.toFloat() / frame.height.toFloat()
        val source = if (sourceAspect > targetAspect) {
            val cropWidth = (frame.height * targetAspect).toInt().coerceAtLeast(1)
            val left = (frame.width - cropWidth) / 2
            Rect(left, 0, left + cropWidth, frame.height)
        } else {
            val cropHeight = (frame.width / targetAspect).toInt().coerceAtLeast(1)
            val top = (frame.height - cropHeight) / 2
            Rect(0, top, frame.width, top + cropHeight)
        }
        canvas.drawBitmap(frame, source, destination, imagePaint)
        drawHands(canvas, destination, source, links)
    }

    private fun drawHands(canvas: Canvas, destination: RectF, crop: Rect?, links: List<Pair<Int, Int>>) {
        val bitmap = cameraFrame
        val fw = (crop?.width() ?: bitmap?.width ?: 1).toFloat().coerceAtLeast(1f)
        val fh = (crop?.height() ?: bitmap?.height ?: 1).toFloat().coerceAtLeast(1f)
        val left = crop?.left?.toFloat() ?: 0f
        val top = crop?.top?.toFloat() ?: 0f
        val scale = minOf(destination.width() / fw, destination.height() / fh)
        val ox = destination.left + (destination.width() - fw * scale) / 2f
        val oy = destination.top + (destination.height() - fh * scale) / 2f
        fun px(p: Pair<Float, Float>) = ox + (p.first * (bitmap?.width ?: fw.toInt()) - left) * scale
        fun py(p: Pair<Float, Float>) = oy + (p.second * (bitmap?.height ?: fh.toInt()) - top) * scale
        handPoints.forEach { hand ->
            links.forEach { (a, b) ->
                if (a < hand.size && b < hand.size) canvas.drawLine(px(hand[a]), py(hand[a]), px(hand[b]), py(hand[b]), linePaint)
            }
            hand.forEach { canvas.drawCircle(px(it), py(it), 5f, pointPaint) }
        }
    }
}
