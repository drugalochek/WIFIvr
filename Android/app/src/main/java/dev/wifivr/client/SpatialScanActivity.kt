package dev.wifivr.client

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Environment
import android.content.ContentValues
import android.provider.MediaStore
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.PointCloud
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class SpatialScanActivity : Activity(), GLSurfaceView.Renderer {
    private var session: Session? = null
    private lateinit var glView: GLSurfaceView
    private lateinit var stats: TextView
    private lateinit var saveButton: Button
    private val points = LinkedHashMap<String, FloatArray>()
    private val planes = LinkedHashMap<String, JSONObject>()
    private var lastStatsMs = 0L
    private var cameraTexture = 0
    private var program = 0
    private var positionHandle = 0
    private var uvHandle = 0
    private var samplerHandle = 0
    private val vertices = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val rawUv = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
    @Volatile private var scanActive = false
    @Volatile private var activityResumed = false
    @Volatile private var waitingForArInstall = false

    private fun floatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(values); position(0) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(8, 13, 25)
        window.navigationBarColor = Color.rgb(8, 13, 25)
        buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 41)
        } else {
            initializeAr()
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(8, 13, 25)) }
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(this@SpatialScanActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(0xD91B2943.toInt())
            elevation = dp(8).toFloat()
        }
        panel.addView(TextView(this).apply {
            text = getString(R.string.room_scanner_title)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        stats = TextView(this).apply {
            text = getString(R.string.ar_checking)
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(0, dp(6), 0, dp(8))
        }
        panel.addView(stats)
        saveButton = Button(this).apply {
            text = getString(R.string.room_scan_save)
            isAllCaps = false
            isEnabled = false
            setOnClickListener { saveScan() }
        }
        panel.addView(saveButton)
        root.addView(glView, FrameLayout.LayoutParams(-1, -1))
        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            topMargin = (if (id > 0) resources.getDimensionPixelSize(id) else dp(24)) + dp(12)
            leftMargin = dp(12)
            rightMargin = dp(12)
        })
        setContentView(root)
    }

    private fun initializeAr() {
        try {
            val installStatus = ArCoreApk.getInstance().requestInstall(this, true)
            if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                waitingForArInstall = true
                return
            }
            waitingForArInstall = false
            if (!ArCoreApk.getInstance().checkAvailability(this).isSupported) {
                stats.text = getString(R.string.ar_unsupported)
                return
            }
            val activeSession = Session(this)
            val config = Config(activeSession)
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            if (activeSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                config.depthMode = Config.DepthMode.AUTOMATIC
            }
            activeSession.configure(config)
            session = activeSession
            scanActive = true
            if (cameraTexture != 0) {
                glView.queueEvent { session?.setCameraTextureNames(intArrayOf(cameraTexture)) }
            }
            stats.text = getString(R.string.room_scan_move_phone)
            if (activityResumed) resumeSession()
        } catch (error: Exception) {
            stats.text = getString(R.string.room_scan_error, error.message ?: "")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            initializeAr()
        } else {
            stats.text = getString(R.string.camera_permission_needed)
        }
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        if (::glView.isInitialized) {
            glView.onResume()
            if (waitingForArInstall && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                initializeAr()
            } else if (scanActive) {
                resumeSession()
            }
        }
    }

    private fun resumeSession() {
        try {
            session?.resume()
        } catch (error: Exception) {
            stats.text = getString(R.string.room_scan_error, error.message ?: "")
        }
    }

    override fun onPause() {
        activityResumed = false
        if (::glView.isInitialized) glView.onPause()
        try {
            session?.pause()
        } catch (_: Exception) {
        }
        super.onPause()
    }

    override fun onDestroy() {
        scanActive = false
        session?.close()
        session = null
        super.onDestroy()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        cameraTexture = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        session?.setCameraTextureNames(intArrayOf(cameraTexture))

        val vertexShader = compileShader(
            GLES20.GL_VERTEX_SHADER,
            "attribute vec4 aPosition; attribute vec2 aUv; varying vec2 vUv; void main(){ gl_Position=aPosition; vUv=aUv; }"
        )
        val fragmentShader = compileShader(
            GLES20.GL_FRAGMENT_SHADER,
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES sTexture; varying vec2 vUv; void main(){ gl_FragColor=texture2D(sTexture,vUv); }"
        )
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        uvHandle = GLES20.glGetAttribLocation(program, "aUv")
        samplerHandle = GLES20.glGetUniformLocation(program, "sTexture")
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val activeSession = session ?: return
        try {
            val frame = activeSession.update()
            val transformedUv = floatBuffer(FloatArray(8))
            frame.transformDisplayUvCoords(rawUv, transformedUv)
            drawCamera(transformedUv)
            collectFrame(frame)
            val now = System.currentTimeMillis()
            if (now - lastStatsMs > 500L) {
                lastStatsMs = now
                val counts = synchronized(this) { planes.size to points.size }
                val message = getString(R.string.room_scan_stats, counts.first, counts.second)
                runOnUiThread {
                    if (!isFinishing) {
                        stats.text = message
                        saveButton.isEnabled = counts.first > 0 || counts.second > 0
                    }
                }
            }
        } catch (error: Exception) {
            runOnUiThread {
                if (!isFinishing) stats.text = getString(R.string.room_scan_error, error.message ?: "")
            }
        }
    }

    private fun drawCamera(uv: FloatBuffer) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertices)
        uv.position(0)
        GLES20.glEnableVertexAttribArray(uvHandle)
        GLES20.glVertexAttribPointer(uvHandle, 2, GLES20.GL_FLOAT, false, 0, uv)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES20.glUniform1i(samplerHandle, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(uvHandle)
    }

    private fun collectFrame(frame: Frame) {
        synchronized(this) {
            frame.getUpdatedTrackables(Plane::class.java).forEach { plane ->
                if (plane.trackingState == TrackingState.TRACKING) {
                    val pose = plane.centerPose
                    val id = System.identityHashCode(plane).toString()
                    planes[id] = JSONObject()
                        .put("id", id)
                        .put("type", plane.type.toString())
                        .put("center", JSONArray(listOf(pose.tx(), pose.ty(), pose.tz())))
                        .put("extent", JSONArray(listOf(plane.extentX, plane.extentZ)))
                }
            }
            val cloud: PointCloud = frame.acquirePointCloud()
            try {
                val buffer = cloud.points
                buffer.position(0)
                val cameraPose = frame.camera.pose
                while (buffer.remaining() >= 4 && points.size < 15000) {
                    val x = buffer.get()
                    val y = buffer.get()
                    val z = buffer.get()
                    val confidence = buffer.get()
                    if (confidence < 0.15f) continue
                    val world = cameraPose.transformPoint(floatArrayOf(x, y, z))
                    val key = "${(world[0] * 20).toInt()}_${(world[1] * 20).toInt()}_${(world[2] * 20).toInt()}"
                    if (!points.containsKey(key)) points[key] = world
                }
            } finally {
                cloud.release()
            }
        }
    }

    private fun saveScan() {
        try {
            val snapshot = synchronized(this) {
                points.values.map { it.copyOf() } to planes.values.map { JSONObject(it.toString()) }
            }
            val pointArray = JSONArray()
            snapshot.first.forEach { pointArray.put(JSONArray(listOf(it[0], it[1], it[2]))) }
            val planeArray = JSONArray()
            snapshot.second.forEach { planeArray.put(it) }
            val result = JSONObject()
                .put("format", "vroom_spatial_scan_v1")
                .put("createdAt", System.currentTimeMillis())
                .put("tracking", "ARCore world coordinates")
                .put("planes", planeArray)
                .put("pointCloud", pointArray)
            val savedLocation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "vroom_room_scan.json")
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VRoom")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Could not create scan file in Downloads")
                contentResolver.openOutputStream(uri)?.use {
                    it.write(result.toString().toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("Could not write scan file")
                uri.toString()
            } else {
                val file = File(filesDir, "vroom_room_scan.json")
                file.writeText(result.toString())
                file.absolutePath
            }
            stats.text = getString(R.string.room_scan_saved, savedLocation, snapshot.first.size, snapshot.second.size)
        } catch (error: Exception) {
            stats.text = getString(R.string.room_scan_error, error.message ?: "")
        }
    }
}
