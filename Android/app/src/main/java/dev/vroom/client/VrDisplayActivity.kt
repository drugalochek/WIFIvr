package dev.vroom.client

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class VrDisplayActivity : Activity() {
    private lateinit var preview: StereoPreviewView
    @Volatile private var running = false
    @Volatile private var connection: Socket? = null
    private var receiver: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        preview = StereoPreviewView(this)
        preview.showStatus("Подключение к VRoom Desktop…")
        setContentView(preview)
        val host = intent.getStringExtra("pc_address")?.trim().orEmpty()
        if (host.isBlank()) {
            preview.showStatus("Не указан адрес компьютера")
            return
        }
        running = true
        receiver = Thread({ receiveFrames(host) }, "VRoom-display-receiver").apply {
            isDaemon = true
            start()
        }
        hideSystemUi()
    }

    private fun receiveFrames(host: String) {
        while (running) {
            var socket: Socket? = null
            try {
                preview.post { preview.showStatus("Подключение к $host:9002…") }
                socket = Socket()
                connection = socket
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, 9002), 5000)
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                preview.post { preview.showStatus("Поток экрана подключён · ожидание кадров") }
                var fpsWindowStart = SystemClock.elapsedRealtime()
                var framesInWindow = 0
                var displayedFps = 0f
                while (running) {
                    val size = input.readInt()
                    if (size !in 4..4_000_000) throw IOException("Некорректный размер кадра: $size")
                    val bytes = ByteArray(size)
                    input.readFully(bytes)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: throw IOException("Не удалось декодировать JPEG-кадр")
                    framesInWindow++
                    val now = SystemClock.elapsedRealtime()
                    if (now - fpsWindowStart >= 1000L) {
                        displayedFps = framesInWindow * 1000f / (now - fpsWindowStart).coerceAtLeast(1L)
                        framesInWindow = 0
                        fpsWindowStart = now
                    }
                    preview.offerFrame(bitmap, displayedFps, size)
                }
            } catch (e: Exception) {
                if (running) {
                    val message = e.message?.take(90) ?: "соединение потеряно"
                    preview.post { preview.showStatus("Нет потока: $message") }
                }
            } finally {
                try { socket?.close() } catch (_: Exception) { }
                if (connection === socket) connection = null
            }
            if (running) {
                try { Thread.sleep(1200) } catch (_: InterruptedException) { }
            }
        }
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onDestroy() {
        running = false
        try { connection?.close() } catch (_: Exception) { }
        receiver?.interrupt()
        preview.release()
        super.onDestroy()
    }
}

private class StereoPreviewView(context: android.content.Context) : View(context) {
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255)
        strokeWidth = 1f
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val telemetryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * resources.displayMetrics.scaledDensity
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }
    private val pendingFrame = AtomicReference<Bitmap?>(null)
    private val frameUpdatePosted = AtomicBoolean(false)
    @Volatile private var frame: Bitmap? = null
    @Volatile private var statusText: String = "Ожидание изображения"
    @Volatile private var receivedFps = 0f
    @Volatile private var frameBytes = 0
    @Volatile private var lastFrameAtMs = 0L

    fun showStatus(value: String) {
        statusText = value
        postInvalidate()
    }

    // Keep at most one UI update queued. If decoding outruns the UI thread,
    // replace the pending bitmap instead of displaying an increasingly stale backlog.
    fun offerFrame(value: Bitmap, fps: Float, bytes: Int) {
        pendingFrame.getAndSet(value)?.let { if (!it.isRecycled) it.recycle() }
        receivedFps = fps
        frameBytes = bytes
        if (frameUpdatePosted.compareAndSet(false, true)) {
            post { applyPendingFrame() }
        }
    }

    private fun applyPendingFrame() {
        val next = pendingFrame.getAndSet(null)
        if (next != null) {
            val previous = frame
            frame = next
            statusText = ""
            lastFrameAtMs = SystemClock.elapsedRealtime()
            if (previous !== next && previous?.isRecycled == false) previous.recycle()
            invalidate()
        }
        frameUpdatePosted.set(false)
        if (pendingFrame.get() != null && frameUpdatePosted.compareAndSet(false, true)) {
            post { applyPendingFrame() }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val current = frame
        if (current == null || current.isRecycled) {
            canvas.drawText(statusText, width / 2f, height / 2f, statusPaint)
            return
        }
        val half = width / 2f
        drawEye(canvas, current, RectF(0f, 0f, half, height.toFloat()), 0)
        drawEye(canvas, current, RectF(half, 0f, width.toFloat(), height.toFloat()), 1)
        canvas.drawLine(half, 0f, half, height.toFloat(), dividerPaint)
        val ageMs = (SystemClock.elapsedRealtime() - lastFrameAtMs).coerceAtLeast(0L)
        val status = if (ageMs > 500L) " · КАДР УСТАРЕЛ" else ""
        val fpsText = if (receivedFps > 0f) "${receivedFps.toInt()} FPS" else "FPS: измерение…"
        val sizeText = "${frameBytes / 1024} KB"
        canvas.drawText("$fpsText · $sizeText · ${ageMs} ms$status", 14f, 24f, telemetryPaint)
    }

    private fun drawEye(canvas: Canvas, bitmap: Bitmap, target: RectF, eye: Int) {
        val eyeWidth = bitmap.width / 2
        val eyeLeft = if (eye == 0) 0 else eyeWidth
        val sourceAspect = eyeWidth.toFloat() / bitmap.height.toFloat()
        val targetAspect = target.width() / target.height()
        val sourceWidth: Float
        val sourceHeight: Float
        if (sourceAspect > targetAspect) {
            sourceWidth = targetAspect * bitmap.height
            sourceHeight = bitmap.height.toFloat()
        } else {
            sourceWidth = eyeWidth.toFloat()
            sourceHeight = eyeWidth / targetAspect
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
