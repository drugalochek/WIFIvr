package dev.wifivr.client

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
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
    private lateinit var cameraExecutor: ExecutorService
    private var handLandmarker: HandLandmarker? = null
    private var lastFrameTimestamp = 0L

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
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener { result, _ ->
                    val hands = result.landmarks().map { hand ->
                        hand.map { landmark -> landmark.x() to landmark.y() }
                    }
                    val wrist = result.landmarks().firstOrNull()?.firstOrNull()
                    runOnUiThread {
                        overlay.setHands(hands)
                        status.text = if (hands.isEmpty()) {
                            getString(R.string.hands_not_found)
                        } else {
                            val coordinates = wrist?.let {
                                getString(R.string.wrist_coordinates, it.x(), it.y(), it.z())
                            } ?: getString(R.string.coordinates_unavailable)
                            getString(R.string.hands_found, hands.size, coordinates)
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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionRequest.launch(Manifest.permission.CAMERA)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildScreen() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(11, 16, 32))
        }
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
        overlay = HandOverlayView(this)
        val infoPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable().apply {
                setColor(0xE6192740.toInt())
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), 0x553D597A)
            }
            elevation = dp(8).toFloat()
        }
        val heading = TextView(this).apply {
            text = getString(R.string.hand_tracking_title)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        status = TextView(this).apply {
            text = getString(R.string.camera_starting)
            textSize = 13f
            setTextColor(Color.rgb(184, 204, 226))
            setPadding(0, dp(5), 0, 0)
        }
        infoPanel.addView(heading)
        infoPanel.addView(status)
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        root.addView(infoPanel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ).apply {
            setMargins(dp(16), dp(20), dp(16), 0)
        })
        setContentView(root)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { image -> analyzeFrame(image) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                status.text = getString(R.string.back_camera_active)
            } catch (error: Exception) {
                status.text = getString(R.string.camera_start_error, error.message ?: "")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(image: ImageProxy) {
        try {
            val bitmap = image.toBitmap()
            val rotation = image.imageInfo.rotationDegrees
            val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = android.graphics.Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )
            overlay.setFrameSize(rotated.width, rotated.height)
            val mpImage = BitmapImageBuilder(rotated).build()
            val now = SystemClock.uptimeMillis()
            val timestamp = maxOf(now, lastFrameTimestamp + 1)
            lastFrameTimestamp = timestamp
            handLandmarker?.detectAsync(mpImage, timestamp)
        } catch (error: Exception) {
            runOnUiThread { status.text = getString(R.string.camera_frame_error, error.message ?: "") }
        } finally {
            image.close()
        }
    }

    override fun onDestroy() {
        handLandmarker?.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}

private class HandOverlayView(context: android.content.Context) : View(context) {
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(73, 226, 178)
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    @Volatile private var hands: List<List<Pair<Float, Float>>> = emptyList()
    @Volatile private var frameWidth = 0
    @Volatile private var frameHeight = 0

    fun setFrameSize(w: Int, h: Int) {
        frameWidth = w
        frameHeight = h
    }

    fun setHands(value: List<List<Pair<Float, Float>>>) {
        hands = value
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val connections = listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 4,
            0 to 5, 5 to 6, 6 to 7, 7 to 8,
            5 to 9, 9 to 10, 10 to 11, 11 to 12,
            9 to 13, 13 to 14, 14 to 15, 15 to 16,
            13 to 17, 17 to 18, 18 to 19, 19 to 20,
            0 to 17
        )
        hands.forEach { hand ->
            val fw = frameWidth.toFloat().coerceAtLeast(1f)
            val fh = frameHeight.toFloat().coerceAtLeast(1f)
            val scale = minOf(width / fw, height / fh)
            val offsetX = (width - fw * scale) / 2f
            val offsetY = (height - fh * scale) / 2f
            fun px(point: Pair<Float, Float>) = offsetX + point.first * fw * scale
            fun py(point: Pair<Float, Float>) = offsetY + point.second * fh * scale
            connections.forEach { (a, b) ->
                if (a < hand.size && b < hand.size) {
                    canvas.drawLine(px(hand[a]), py(hand[a]), px(hand[b]), py(hand[b]), linePaint)
                }
            }
            hand.forEach { point -> canvas.drawCircle(px(point), py(point), 8f, pointPaint) }
        }
    }
}
