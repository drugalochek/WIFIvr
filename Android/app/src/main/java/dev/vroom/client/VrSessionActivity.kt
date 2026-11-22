package dev.vroom.client

import android.Manifest
import androidx.activity.ComponentActivity
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import android.view.View
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.BufferedInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

/**
 * Single Android runtime for VRoom. Head IMU, MediaPipe hands, phone-camera upload,
 * and the incoming stereo display all run in this Activity so one feature cannot
 * pause another by taking over the Activity lifecycle.
 */
class VrSessionActivity : ComponentActivity(), SensorEventListener {
    private lateinit var preview: StereoSessionView
    private lateinit var sensors: SensorManager
    private var rotationSensor: Sensor? = null
    private var handLandmarker: HandLandmarker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val cameraSendExecutor = Executors.newSingleThreadExecutor()
    private var headSender: ScheduledExecutorService? = null
    @Volatile private var running = false
    @Volatile private var target: InetAddress? = null
    @Volatile private var telemetrySocket: DatagramSocket? = null
    @Volatile private var cameraSocket: Socket? = null
    @Volatile private var cameraOutput: DataOutputStream? = null
    @Volatile private var displaySocket: Socket? = null
    private var displayThread: Thread? = null
    private val latestOrientation = AtomicReference<FloatArray?>(null)
    private val handSequence = AtomicLong(0)
    private val cameraFrameInFlight = AtomicBoolean(false)
    private val smoothedHands = mutableMapOf<String, MutableList<FloatArray>>()
    private val smoothedWorldHands = mutableMapOf<String, MutableList<FloatArray>>()
    private val smoothingAlpha = 0.42f
    private var lastFrameTimestamp = 0L
    @Volatile private var analysisWidth = 640
    @Volatile private var analysisHeight = 360
    private var lastCameraSendMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        preview = StereoSessionView(this)
        preview.showStatus("Запуск VR-сеанса…")
        setContentView(preview)

        val host = intent.getStringExtra("pc_address")?.trim().orEmpty()
        if (host.isBlank()) {
            preview.showStatus("Не указан адрес компьютера")
            return
        }
        running = true
        startNetwork(host)
        createHandTracker()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
        }
        hideSystemUi()
    }

    private fun createHandTracker() {
        try {
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.35f)
                .setMinHandPresenceConfidence(0.35f)
                .setMinTrackingConfidence(0.35f)
                .setResultListener { result, _ -> sendHandPose(result) }
                .setErrorListener { error ->
                    preview.post { preview.showStatus("Ошибка MediaPipe: ${error.message ?: "unknown"}") }
                }
                .build()
            handLandmarker = HandLandmarker.createFromOptions(this, options)
        } catch (error: Exception) {
            preview.showStatus("Не удалось запустить трекинг рук: ${error.message ?: ""}")
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!running || isFinishing) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider
                val rotation = preview.display?.rotation ?: Surface.ROTATION_0
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(640, 360))
                    .setTargetRotation(rotation)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraExecutor) { image ->
                    try {
                        val bitmap = image.toBitmap()
                        val matrix = android.graphics.Matrix().apply {
                            postRotate(image.imageInfo.rotationDegrees.toFloat())
                        }
                        val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                        analysisWidth = upright.width
                        analysisHeight = upright.height
                        sendCameraFrame(upright)
                        val mpImage = BitmapImageBuilder(upright).build()
                        val timestamp = maxOf(SystemClock.uptimeMillis(), lastFrameTimestamp + 1)
                        lastFrameTimestamp = timestamp
                        handLandmarker?.detectAsync(mpImage, timestamp)
                    } catch (error: Exception) {
                        preview.post { preview.showStatus("Ошибка камеры: ${error.message ?: ""}") }
                    } finally {
                        image.close()
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                preview.showStatus("Камера активна · ищу руки · подключаю SteamVR")
            } catch (error: Exception) {
                preview.showStatus("Не удалось открыть камеру: ${error.message ?: ""}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startNetwork(host: String) {
        networkExecutor.execute {
            try {
                val resolvedAddress = InetAddress.getByName(host)
                target = resolvedAddress
                telemetrySocket = DatagramSocket()
                preview.post { preview.showStatus("Связь с ПК установлена · запуск трекинга") }
                startHeadSender()
                while (running && !Thread.currentThread().isInterrupted) {
                    var socket: Socket? = null
                    try {
                        socket = Socket()
                        socket.tcpNoDelay = true
                        socket.connect(InetSocketAddress(resolvedAddress, 9001), 2500)
                        cameraSocket = socket
                        cameraOutput = DataOutputStream(socket.getOutputStream())
                        preview.post { preview.setConnectionText("ПК подключён · камера и руки передаются") }
                        while (running && socket.isConnected && !socket.isClosed) {
                            Thread.sleep(250)
                        }
                    } catch (error: Exception) {
                        if (running) preview.post {
                            preview.setConnectionText("Камера ПК: переподключение…")
                        }
                    } finally {
                        try { socket?.close() } catch (_: Exception) { }
                        if (cameraSocket === socket) cameraSocket = null
                        cameraOutput = null
                    }
                    if (running) Thread.sleep(900)
                }
            } catch (error: Exception) {
                preview.post { preview.showStatus("Ошибка подключения к ПК: ${error.message ?: ""}") }
            }
        }
        displayThread = Thread({ receiveDisplay(host) }, "VRoom-stereo-receiver").apply {
            isDaemon = true
            start()
        }
    }

    private fun startHeadSender() {
        headSender?.shutdownNow()
        headSender = Executors.newSingleThreadScheduledExecutor()
        headSender?.scheduleAtFixedRate({
            val q = latestOrientation.get() ?: return@scheduleAtFixedRate
            val address = target ?: return@scheduleAtFixedRate
            val socket = telemetrySocket ?: return@scheduleAtFixedRate
            try {
                val json = JSONObject()
                    .put("type", "head_pose")
                    .put("version", 1)
                    .put("timestampNs", System.nanoTime())
                    .put("sequence", System.nanoTime())
                    .put("orientation", JSONObject()
                        .put("x", q[0].toDouble()).put("y", q[1].toDouble())
                        .put("z", q[2].toDouble()).put("w", q[3].toDouble()))
                    .toString().toByteArray(Charsets.UTF_8)
                synchronized(socket) { socket.send(DatagramPacket(json, json.size, address, 9000)) }
            } catch (_: Exception) { }
        }, 0, 16, TimeUnit.MILLISECONDS)
    }

    private fun sendHandPose(result: HandLandmarkerResult) {
        val address = target ?: return
        val socket = telemetrySocket ?: return
        try {
            val hands = JSONArray()
            val seen = mutableSetOf<String>()
            result.landmarks().forEachIndexed { index, landmarks ->
                val category = result.handedness().getOrNull(index)?.firstOrNull()
                val side = category?.categoryName() ?: return@forEachIndexed
                if (side != "Left" && side != "Right" || landmarks.size != 21) return@forEachIndexed
                seen.add(side)
                val previous = smoothedHands[side]
                val filtered = landmarks.mapIndexed { pointIndex, point ->
                    val raw = floatArrayOf(point.x(), point.y(), point.z())
                    val old = previous?.getOrNull(pointIndex)
                    if (old == null) raw else FloatArray(3) { axis ->
                        old[axis] + smoothingAlpha * (raw[axis] - old[axis])
                    }
                }
                smoothedHands[side] = filtered.toMutableList()
                val imagePoints = JSONArray()
                filtered.forEach { p ->
                    imagePoints.put(JSONObject().put("x", p[0].coerceIn(0f, 1f).toDouble())
                        .put("y", p[1].coerceIn(0f, 1f).toDouble()).put("z", p[2].toDouble()))
                }
                val worldPoints = JSONArray()
                val world = result.worldLandmarks().getOrNull(index)
                if (world != null && world.size == 21) {
                    val oldWorld = smoothedWorldHands[side]
                    val filteredWorld = world.mapIndexed { pointIndex, point ->
                        val raw = floatArrayOf(point.x(), point.y(), point.z())
                        val old = oldWorld?.getOrNull(pointIndex)
                        if (old == null) raw else FloatArray(3) { axis ->
                            old[axis] + smoothingAlpha * (raw[axis] - old[axis])
                        }
                    }
                    smoothedWorldHands[side] = filteredWorld.toMutableList()
                    filteredWorld.forEach { p ->
                        worldPoints.put(JSONObject().put("x", p[0].toDouble())
                            .put("y", p[1].toDouble()).put("z", p[2].toDouble()))
                    }
                } else smoothedWorldHands.remove(side)

                val thumb = filtered[4]
                val tip = filtered[8]
                val pinchDistance = sqrt((thumb[0] - tip[0]) * (thumb[0] - tip[0]) +
                    (thumb[1] - tip[1]) * (thumb[1] - tip[1]))
                val pinch = ((0.22f - pinchDistance) / 0.17f).coerceIn(0f, 1f)
                val gesture = when {
                    pinch >= 0.75f -> "pinch"
                    isFingerExtended(filtered, 8) && !isFingerExtended(filtered, 12) &&
                        !isFingerExtended(filtered, 16) && !isFingerExtended(filtered, 20) -> "point"
                    (1..4).none { isFingerExtended(filtered, it * 4) } -> "fist"
                    (1..4).all { isFingerExtended(filtered, it * 4) } -> "open_palm"
                    else -> "unknown"
                }
                // Monocular root estimate: infer wrist depth from the apparent palm width.
                // 85 mm is a nominal palm breadth; this is only a startup estimate until the
                // user calibrates hand scale and the phone-camera-to-HMD transform.
                val frameWidth = analysisWidth.toFloat().coerceAtLeast(1f)
                val frameHeight = analysisHeight.toFloat().coerceAtLeast(1f)
                val palmDx = (filtered[5][0] - filtered[17][0]) * frameWidth
                val palmDy = (filtered[5][1] - filtered[17][1]) * frameHeight
                val palmPixels = sqrt(palmDx * palmDx + palmDy * palmDy).coerceAtLeast(1f)
                val focalPixels = frameWidth * 0.92f
                val depthMeters = (focalPixels * 0.085f / palmPixels).coerceIn(0.18f, 1.50f)
                val wristX = filtered[0][0] * frameWidth
                val wristY = filtered[0][1] * frameHeight
                val cameraX = (wristX - frameWidth * 0.5f) * depthMeters / focalPixels
                val cameraY = (wristY - frameHeight * 0.5f) * depthMeters / focalPixels
                val rootPosition = JSONObject()
                    .put("x", cameraX.toDouble())
                    .put("y", (-cameraY + 0.02f).toDouble())
                    .put("z", (-depthMeters - 0.08f).toDouble())

                hands.put(JSONObject().put("handedness", side)
                    .put("confidence", (category?.score() ?: 0f).coerceIn(0f, 1f).toDouble())
                    .put("gesture", gesture).put("pinch", pinch.toDouble())
                    .put("landmarks", imagePoints).put("worldLandmarks", worldPoints)
                    .put("rootPosition", rootPosition))
            }
            smoothedHands.keys.retainAll(seen)
            smoothedWorldHands.keys.retainAll(seen)
            val bytes = JSONObject().put("type", "hand_pose").put("version", 2)
                .put("timestampNs", System.nanoTime()).put("sequence", handSequence.getAndIncrement())
                .put("hands", hands).toString().toByteArray(Charsets.UTF_8)
            synchronized(socket) { socket.send(DatagramPacket(bytes, bytes.size, address, 9000)) }
            preview.post { preview.setTrackingText(if (seen.isEmpty()) "Руки не обнаружены" else "Отслеживается рук: ${seen.size}") }
        } catch (_: Exception) { }
    }

    private fun isFingerExtended(points: List<FloatArray>, tipIndex: Int): Boolean {
        val baseIndex = when (tipIndex) { 4 -> 2; 8 -> 5; 12 -> 9; 16 -> 13; else -> 17 }
        return points[tipIndex][1] < points[baseIndex][1] - 0.025f
    }

    private fun sendCameraFrame(bitmap: Bitmap) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCameraSendMs < 100L) return
        val output = cameraOutput ?: return
        val socket = cameraSocket ?: return
        if (!socket.isConnected || socket.isClosed || !cameraFrameInFlight.compareAndSet(false, true)) return
        lastCameraSendMs = now
        try {
            val bytes = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 48, bytes)
            val frame = bytes.toByteArray()
            if (frame.isEmpty() || frame.size > 1_500_000) {
                cameraFrameInFlight.set(false)
                return
            }
            cameraSendExecutor.execute {
                try {
                    synchronized(output) {
                        output.writeInt(frame.size)
                        output.write(frame)
                        output.flush()
                    }
                } catch (_: Exception) {
                    try { socket.close() } catch (_: Exception) { }
                    cameraOutput = null
                    cameraSocket = null
                } finally {
                    cameraFrameInFlight.set(false)
                }
            }
        } catch (_: Exception) {
            cameraFrameInFlight.set(false)
        }
    }

    private fun receiveDisplay(host: String) {
        while (running) {
            var socket: Socket? = null
            try {
                preview.post { preview.setConnectionText("Подключение к SteamVR-экрану $host:9002…") }
                socket = Socket()
                displaySocket = socket
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, 9002), 5000)
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                preview.post { preview.setConnectionText("Канал SteamVR подключён · ожидание первого кадра…") }
                var windowStart = SystemClock.elapsedRealtime()
                var frameCount = 0
                var fps = 0f
                var firstFrameShown = false
                while (running) {
                    val size = input.readInt()
                    if (size !in 4..4_000_000) throw IOException("Некорректный размер кадра: $size")
                    val bytes = ByteArray(size)
                    input.readFully(bytes)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: throw IOException("Не удалось декодировать JPEG")
                    if (!firstFrameShown) {
                        firstFrameShown = true
                        preview.post { preview.setConnectionText("Первый кадр SteamVR получен · изображение выводится") }
                    }
                    frameCount++
                    val now = SystemClock.elapsedRealtime()
                    if (now - windowStart >= 1000L) {
                        fps = frameCount * 1000f / (now - windowStart).coerceAtLeast(1L)
                        frameCount = 0
                        windowStart = now
                    }
                    preview.offerFrame(bitmap, fps, size)
                }
            } catch (error: Exception) {
                if (running) preview.post {
                    preview.setConnectionText("Нет изображения SteamVR: ${error.message?.take(70) ?: "нет связи"}")
                }
            } finally {
                try { socket?.close() } catch (_: Exception) { }
                if (displaySocket === socket) displaySocket = null
            }
            if (running) {
                try { Thread.sleep(1000) } catch (_: InterruptedException) { }
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val quaternion = FloatArray(4)
        SensorManager.getQuaternionFromVector(quaternion, event.values)
        latestOrientation.set(floatArrayOf(quaternion[1], quaternion[2], quaternion[3], quaternion[0]))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onResume() {
        super.onResume()
        rotationSensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        sensors.unregisterListener(this)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else if (requestCode == CAMERA_PERMISSION_REQUEST) {
            preview.showStatus("Без доступа к камере трекинг рук не запустится")
        }
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onDestroy() {
        running = false
        sensors.unregisterListener(this)
        headSender?.shutdownNow()
        cameraProvider?.unbindAll()
        try { telemetrySocket?.close() } catch (_: Exception) { }
        try { cameraSocket?.close() } catch (_: Exception) { }
        try { displaySocket?.close() } catch (_: Exception) { }
        displayThread?.interrupt()
        handLandmarker?.close()
        smoothedHands.clear()
        smoothedWorldHands.clear()
        cameraExecutor.shutdownNow()
        cameraSendExecutor.shutdownNow()
        networkExecutor.shutdownNow()
        preview.release()
        super.onDestroy()
    }

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 42
    }
}

private class StereoSessionView(context: android.content.Context) : View(context) {
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255); strokeWidth = 1f }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 18f * resources.displayMetrics.scaledDensity; textAlign = Paint.Align.CENTER
    }
    private val telemetryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 12f * resources.displayMetrics.scaledDensity
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }
    private val pendingFrame = AtomicReference<Bitmap?>(null)
    private val frameUpdatePosted = AtomicBoolean(false)
    @Volatile private var frame: Bitmap? = null
    @Volatile private var statusText = "Ожидание изображения SteamVR"
    @Volatile private var connectionText = "ПК не подключён"
    @Volatile private var trackingText = "Трекинг запускается"
    @Volatile private var fps = 0f
    @Volatile private var bytes = 0
    @Volatile private var lastFrameAtMs = 0L

    fun showStatus(value: String) { statusText = value; postInvalidate() }
    fun setConnectionText(value: String) { connectionText = value; postInvalidate() }
    fun setTrackingText(value: String) { trackingText = value; postInvalidate() }

    fun offerFrame(value: Bitmap, frameRate: Float, size: Int) {
        pendingFrame.getAndSet(value)?.let { if (!it.isRecycled) it.recycle() }
        fps = frameRate; bytes = size
        if (frameUpdatePosted.compareAndSet(false, true)) post { applyPendingFrame() }
    }

    private fun applyPendingFrame() {
        val next = pendingFrame.getAndSet(null)
        if (next != null) {
            val old = frame
            frame = next
            lastFrameAtMs = SystemClock.elapsedRealtime()
            if (old !== next && old?.isRecycled == false) old.recycle()
            invalidate()
        }
        frameUpdatePosted.set(false)
        if (pendingFrame.get() != null && frameUpdatePosted.compareAndSet(false, true)) post { applyPendingFrame() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val current = frame
        if (current == null || current.isRecycled) {
            canvas.drawText(statusText, width / 2f, height / 2f, statusPaint)
        } else {
            val half = width / 2f
            drawEye(canvas, current, RectF(0f, 0f, half, height.toFloat()), 0)
            drawEye(canvas, current, RectF(half, 0f, width.toFloat(), height.toFloat()), 1)
            canvas.drawLine(half, 0f, half, height.toFloat(), dividerPaint)
            val age = (SystemClock.elapsedRealtime() - lastFrameAtMs).coerceAtLeast(0L)
            val rate = if (fps > 0f) "${fps.toInt()} FPS" else "FPS: —"
            canvas.drawText("$rate · ${bytes / 1024} KB · ${age} ms", 14f, 24f, telemetryPaint)
        }
        canvas.drawText(connectionText, width / 2f, height - 40f, telemetryPaint)
        canvas.drawText(trackingText, width / 2f, height - 18f, telemetryPaint)
    }

    private fun drawEye(canvas: Canvas, bitmap: Bitmap, target: RectF, eye: Int) {
        val eyeWidth = bitmap.width / 2
        val eyeLeft = if (eye == 0) 0 else eyeWidth
        val sourceAspect = eyeWidth.toFloat() / bitmap.height.toFloat()
        val targetAspect = target.width() / target.height().coerceAtLeast(1f)
        val sourceWidth: Float
        val sourceHeight: Float
        if (sourceAspect > targetAspect) {
            sourceWidth = targetAspect * bitmap.height; sourceHeight = bitmap.height.toFloat()
        } else {
            sourceWidth = eyeWidth.toFloat(); sourceHeight = eyeWidth / targetAspect
        }
        val left = eyeLeft + (eyeWidth - sourceWidth) / 2f
        val top = (bitmap.height - sourceHeight) / 2f
        val source = Rect(left.toInt(), top.toInt(), (left + sourceWidth).toInt(), (top + sourceHeight).toInt())
        canvas.drawBitmap(bitmap, source, target, bitmapPaint)
    }

    fun release() {
        pendingFrame.getAndSet(null)?.let { if (!it.isRecycled) it.recycle() }
        val old = frame
        frame = null
        if (old?.isRecycled == false) old.recycle()
    }
}
