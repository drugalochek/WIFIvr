package dev.vroom.client

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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Socket
import java.io.DataOutputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

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
    private var boundPreview: Preview? = null
    private var boundAnalysis: ImageAnalysis? = null
    @Volatile private var handDestination: InetAddress? = null
    @Volatile private var handSocket: DatagramSocket? = null
    private val handSequence = AtomicLong(0)
    // EMA filters are keyed by handedness and landmark index.
    private val smoothedHands = mutableMapOf<String, MutableList<FloatArray>>()
    private val smoothedWorldHands = mutableMapOf<String, MutableList<FloatArray>>()
    private val handSmoothingAlpha = 0.42f
    private val cameraStreamExecutor = Executors.newSingleThreadExecutor()
    private val cameraConnectionExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var cameraConnectionRunning = true
    private val cameraFrameInFlight = AtomicBoolean(false)
    @Volatile private var cameraStreamSocket: Socket? = null
    @Volatile private var cameraStreamOutput: DataOutputStream? = null
    private var lastCameraSendMs = 0L

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
        val pcAddress = intent.getStringExtra("pc_address")?.trim().orEmpty()
        if (pcAddress.isNotEmpty()) {
            cameraConnectionExecutor.execute {
                try {
                    handDestination = InetAddress.getByName(pcAddress)
                    handSocket = DatagramSocket()
                } catch (error: Exception) {
                    runOnUiThread { status.text = "Ошибка адреса ПК: ${error.message ?: ""}" }
                    return@execute
                }
                while (cameraConnectionRunning && !Thread.currentThread().isInterrupted) {
                    val current = cameraStreamSocket
                    if (current == null || current.isClosed || !current.isConnected) {
                        try {
                            val stream = Socket()
                            stream.tcpNoDelay = true
                            stream.connect(java.net.InetSocketAddress(handDestination, 9001), 2000)
                            cameraStreamSocket = stream
                            cameraStreamOutput = DataOutputStream(stream.getOutputStream())
                            runOnUiThread { status.text = "Камера подключена к VRoom Desktop · TCP 9001" }
                        } catch (error: Exception) {
                            runOnUiThread { status.text = "Ожидание VRoom Desktop · повторное подключение..." }
                            try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
                        }
                    } else {
                        try { Thread.sleep(500) } catch (_: InterruptedException) { break }
                    }
                }
            }
        }
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
                    sendHandPose(result)
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

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Do not unbind/rebind the camera during a headset orientation change.
        // Update CameraX use-case rotations in place to avoid a frozen analyzer/preview.
        val rotation = previewView.display?.rotation ?: windowManager.defaultDisplay.rotation
        boundPreview?.targetRotation = rotation
        boundAnalysis?.targetRotation = rotation
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            // Keep a sharper 720p preview while MediaPipe processes a lighter 16:9 frame.
            // Explicitly disable front-camera mirroring so the preview matches real-world coordinates.
            val displayRotation = previewView.display?.rotation ?: windowManager.defaultDisplay.rotation
            val previewBuilder = Preview.Builder()
                .setTargetResolution(Size(1280, 720))
                .setTargetRotation(displayRotation)
                .setMirrorMode(MirrorMode.MIRROR_MODE_OFF)
            val preview = previewBuilder.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val analysisBuilder = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 360))
                .setTargetRotation(displayRotation)
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
            boundPreview = preview
            boundAnalysis = analysis
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

    private fun sendHandPose(result: com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult) {
        val target = handDestination ?: return
        val socket = handSocket ?: return
        try {
            val handsJson = JSONArray()
            val seenSides = mutableSetOf<String>()
            result.landmarks().forEachIndexed { index, landmarks ->
                val category = result.handedness().getOrNull(index)?.firstOrNull()
                val handedness = category?.categoryName() ?: return@forEachIndexed
                if (handedness != "Left" && handedness != "Right") return@forEachIndexed
                seenSides.add(handedness)
                if (landmarks.size != 21) return@forEachIndexed

                val previous = smoothedHands[handedness]
                val filtered = landmarks.mapIndexed { pointIndex, point ->
                    val raw = floatArrayOf(point.x(), point.y(), point.z())
                    val old = previous?.getOrNull(pointIndex)
                    if (old == null) raw else floatArrayOf(
                        old[0] + handSmoothingAlpha * (raw[0] - old[0]),
                        old[1] + handSmoothingAlpha * (raw[1] - old[1]),
                        old[2] + handSmoothingAlpha * (raw[2] - old[2])
                    )
                }
                smoothedHands[handedness] = filtered.toMutableList()

                val imagePoints = JSONArray()
                filtered.forEach { point ->
                    imagePoints.put(JSONObject()
                        .put("x", point[0].coerceIn(0f, 1f).toDouble())
                        .put("y", point[1].coerceIn(0f, 1f).toDouble())
                        .put("z", point[2].toDouble()))
                }

                // MediaPipe world landmarks are hand-centred metric estimates, not camera- or room-space positions.
                val worldPoints = JSONArray()
                val worldHand = result.worldLandmarks().getOrNull(index)
                if (worldHand != null && worldHand.size == 21) {
                    val previousWorld = smoothedWorldHands[handedness]
                    val filteredWorld = worldHand.mapIndexed { pointIndex, point ->
                        val raw = floatArrayOf(point.x(), point.y(), point.z())
                        val old = previousWorld?.getOrNull(pointIndex)
                        if (old == null) raw else floatArrayOf(
                            old[0] + handSmoothingAlpha * (raw[0] - old[0]),
                            old[1] + handSmoothingAlpha * (raw[1] - old[1]),
                            old[2] + handSmoothingAlpha * (raw[2] - old[2])
                        )
                    }
                    smoothedWorldHands[handedness] = filteredWorld.toMutableList()
                    filteredWorld.forEach { point ->
                        worldPoints.put(JSONObject()
                            .put("x", point[0].toDouble())
                            .put("y", point[1].toDouble())
                            .put("z", point[2].toDouble()))
                    }
                } else {
                    smoothedWorldHands.remove(handedness)
                }

                val thumb = filtered[4]
                val indexTip = filtered[8]
                val pinchDistance = kotlin.math.sqrt(
                    (thumb[0] - indexTip[0]) * (thumb[0] - indexTip[0]) +
                    (thumb[1] - indexTip[1]) * (thumb[1] - indexTip[1])
                )
                val pinch = ((0.22f - pinchDistance) / 0.17f).coerceIn(0f, 1f)
                val gesture = when {
                    pinch >= 0.75f -> "pinch"
                    isFingerExtended(filtered, 8) && !isFingerExtended(filtered, 12) &&
                        !isFingerExtended(filtered, 16) && !isFingerExtended(filtered, 20) -> "point"
                    (1..4).none { isFingerExtended(filtered, it * 4) } -> "fist"
                    (1..4).all { isFingerExtended(filtered, it * 4) } -> "open_palm"
                    else -> "unknown"
                }
                handsJson.put(JSONObject()
                    .put("handedness", handedness)
                    .put("confidence", (category?.score() ?: 0f).coerceIn(0f, 1f).toDouble())
                    .put("gesture", gesture)
                    .put("pinch", pinch.toDouble())
                    .put("landmarks", imagePoints)
                    .put("worldLandmarks", worldPoints))
            }

            // Forget a missing side immediately so reacquisition cannot blend with a stale pose.
            smoothedHands.keys.retainAll(seenSides)
            smoothedWorldHands.keys.retainAll(seenSides)

            val payload = JSONObject()
                .put("type", "hand_pose")
                .put("version", 2)
                .put("timestampNs", System.nanoTime())
                .put("sequence", handSequence.getAndIncrement())
                .put("hands", handsJson)
                .toString()
                .toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(payload, payload.size, target, 9000))
        } catch (_: Exception) {
            // A dropped tracking datagram is preferable to blocking camera inference.
        }
    }

    private fun isFingerExtended(points: List<FloatArray>, tipIndex: Int): Boolean {
        val baseIndex = when (tipIndex) { 4 -> 2; 8 -> 5; 12 -> 9; 16 -> 13; else -> 17 }
        // Image y grows downwards. This is a simple gesture heuristic, not a pose solve.
        return points[tipIndex][1] < points[baseIndex][1] - 0.025f
    }

    private fun sendCameraFrame(bitmap: Bitmap) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCameraSendMs < 100L) return
        val output = cameraStreamOutput ?: return
        val socket = cameraStreamSocket ?: return
        if (!socket.isConnected || socket.isClosed || !cameraFrameInFlight.compareAndSet(false, true)) return
        lastCameraSendMs = now
        try {
            val bytes = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 55, bytes)
            val frame = bytes.toByteArray()
            if (frame.isEmpty() || frame.size > 1_500_000) {
                cameraFrameInFlight.set(false)
                return
            }
            cameraStreamExecutor.execute {
                try {
                    synchronized(output) {
                        output.writeInt(frame.size)
                        output.write(frame)
                        output.flush()
                    }
                } catch (_: Exception) {
                    try { socket.close() } catch (_: Exception) { }
                    cameraStreamOutput = null
                    cameraStreamSocket = null
                } finally {
                    cameraFrameInFlight.set(false)
                }
            }
        } catch (_: Exception) {
            cameraFrameInFlight.set(false)
        }
    }

    private fun analyzeFrame(image: androidx.camera.core.ImageProxy) {
        try {
            val bitmap = image.toBitmap()
            // CameraX rotationDegrees is relative to the use-case target rotation.
            // Applying it exactly once keeps portrait and headset-landscape frames upright.
            val matrix = android.graphics.Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
            val rotated = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            // Keep the sensor image unmirrored in both phone and headset modes.
            // Hand landmarks are generated from this exact same bitmap, so they stay aligned.
            val modelBitmap = rotated
            overlay.updateFrame(modelBitmap)
            sendCameraFrame(modelBitmap)
            val mpImage = BitmapImageBuilder(modelBitmap).build()
            val timestamp = maxOf(SystemClock.uptimeMillis(), lastFrameTimestamp + 1)
            lastFrameTimestamp = timestamp
            handLandmarker?.detectAsync(mpImage, timestamp)
        } catch (error: Exception) {
            runOnUiThread { status.text = getString(R.string.camera_frame_error, error.message ?: "") }
        } finally { image.close() }
    }

    override fun onDestroy() {
        smoothedHands.clear()
        smoothedWorldHands.clear()
        handLandmarker?.close()
        handSocket?.close()
        handSocket = null
        cameraConnectionRunning = false
        try { cameraStreamSocket?.close() } catch (_: Exception) { }
        cameraStreamSocket = null
        cameraStreamOutput = null
        cameraStreamExecutor.shutdownNow()
        cameraConnectionExecutor.shutdownNow()
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
        // Use the same unmirrored camera coordinates as the normal phone preview.
        // Do not add a second transform here: CameraX already supplies rotation metadata.
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
