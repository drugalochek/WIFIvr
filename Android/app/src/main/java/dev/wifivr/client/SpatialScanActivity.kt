package dev.wifivr.client

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Environment
import android.content.ContentValues
import android.provider.MediaStore
import android.view.Gravity
import android.view.Surface
import android.content.pm.ActivityInfo
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
    private val meshTriangles = LinkedHashMap<String, FloatArray>()
    @Volatile private var renderMeshVertices = FloatArray(0)
    private val maxScanPoints = 120000
    private val maxMeshTriangles = 24000
    private var lastStatsMs = 0L
    @Volatile private var depthSupported = false
    @Volatile private var depthFrames = 0L
    @Volatile private var lastTrackingState = TrackingState.STOPPED
    @Volatile private var lastTrackingFailure = ""
    private var cameraTexture = 0
    private var program = 0
    private var positionHandle = 0
    private var uvHandle = 0
    private var samplerHandle = 0
    private var gridProgram = 0
    private var gridPositionHandle = 0
    private var gridMvpHandle = 0
    private var gridColorHandle = 0
    private var pointProgram = 0
    private var pointPositionHandle = 0
    private var pointMvpHandle = 0
    private val vertices = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val rawUv = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
    @Volatile private var scanActive = false
    @Volatile private var activityResumed = false
    @Volatile private var waitingForArInstall = false
    @Volatile private var surfaceWidth = 0
    @Volatile private var surfaceHeight = 0

    private fun floatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(values); position(0) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep AR scanning in the same upright portrait orientation as the normal camera view.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
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
            depthSupported = activeSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            if (depthSupported) config.depthMode = Config.DepthMode.AUTOMATIC
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
        GLES20.glClearColor(0.03f, 0.05f, 0.09f, 1f)
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

        val gridVertex = compileShader(
            GLES20.GL_VERTEX_SHADER,
            "attribute vec3 aPosition; uniform mat4 uMvp; void main(){ gl_Position=uMvp*vec4(aPosition,1.0); }"
        )
        val gridFragment = compileShader(
            GLES20.GL_FRAGMENT_SHADER,
            "precision mediump float; uniform vec4 uColor; void main(){ gl_FragColor=uColor; }"
        )
        gridProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(gridProgram, gridVertex)
        GLES20.glAttachShader(gridProgram, gridFragment)
        GLES20.glLinkProgram(gridProgram)
        gridPositionHandle = GLES20.glGetAttribLocation(gridProgram, "aPosition")
        gridMvpHandle = GLES20.glGetUniformLocation(gridProgram, "uMvp")
        gridColorHandle = GLES20.glGetUniformLocation(gridProgram, "uColor")

        // Render the accumulated depth/feature cloud over the live camera image.
        val pointVertex = compileShader(
            GLES20.GL_VERTEX_SHADER,
            "attribute vec3 aPosition; uniform mat4 uMvp; void main(){ gl_Position=uMvp*vec4(aPosition,1.0); gl_PointSize=4.5; }"
        )
        val pointFragment = compileShader(
            GLES20.GL_FRAGMENT_SHADER,
            "precision mediump float; void main(){ vec2 p=gl_PointCoord-vec2(0.5); if(dot(p,p)>0.25) discard; gl_FragColor=vec4(0.15,0.95,1.0,0.92); }"
        )
        pointProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(pointProgram, pointVertex)
        GLES20.glAttachShader(pointProgram, pointFragment)
        GLES20.glLinkProgram(pointProgram)
        pointPositionHandle = GLES20.glGetAttribLocation(pointProgram, "aPosition")
        pointMvpHandle = GLES20.glGetUniformLocation(pointProgram, "uMvp")
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val activeSession = session ?: return
        try {
            if (surfaceWidth > 0 && surfaceHeight > 0) {
                // Match the physical display rotation exactly. Do not add a 180-degree
                // correction or mirror the UVs: ARCore's transformed display UVs already
                // account for sensor orientation. An extra half-turn makes the whole scene
                // appear upside down and misaligns the depth overlay.
                val displayRotation = glView.display?.rotation ?: windowManager.defaultDisplay.rotation
                activeSession.setDisplayGeometry(displayRotation, surfaceWidth, surfaceHeight)
            }
            val frame = activeSession.update()
            lastTrackingState = frame.camera.trackingState
            lastTrackingFailure = if (lastTrackingState == TrackingState.TRACKING) "" else frame.camera.trackingFailureReason.toString()
            val transformedUv = floatBuffer(FloatArray(8))
            // Rewind buffers because ARCore advances their positions while transforming UVs.
            rawUv.position(0)
            transformedUv.position(0)
            frame.transformDisplayUvCoords(rawUv, transformedUv)
            transformedUv.position(0)
            collectFrame(frame)
            drawCamera(transformedUv)
            drawDepthPointCloud(frame)
            drawDepthMesh(frame)
            drawDetectedPlaneGrid(activeSession, frame)
            val now = System.currentTimeMillis()
            if (now - lastStatsMs > 500L) {
                lastStatsMs = now
                val counts = synchronized(this) { planes.size to points.size }
                val depthStatus = if (!depthSupported) "Depth API недоступен" else "Depth-кадров: $depthFrames"
                val trackingStatus = if (lastTrackingState == TrackingState.TRACKING) "Камера: tracking" else "Камера: $lastTrackingState ($lastTrackingFailure)"
                val message = getString(R.string.room_scan_stats, counts.first, counts.second) + "\n$trackingStatus · $depthStatus"
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

    /**
     * Draws ARCore's tracked horizontal and vertical plane polygons as a world-anchored
     * wire grid. This is live geometry, not a completed semantic furniture scan.
     */
    private fun drawDetectedPlaneGrid(activeSession: Session, frame: Frame) {
        if (gridProgram == 0) return
        val view = FloatArray(16)
        val projection = FloatArray(16)
        val viewProjection = FloatArray(16)
        frame.camera.getViewMatrix(view, 0)
        frame.camera.getProjectionMatrix(projection, 0, 0.1f, 100f)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)

        GLES20.glUseProgram(gridProgram)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glLineWidth(2f)
        GLES20.glEnableVertexAttribArray(gridPositionHandle)

        activeSession.getAllTrackables(Plane::class.java).forEach { plane ->
            if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) return@forEach
            val polygonBuffer = plane.polygon
            val polygon = ArrayList<Pair<Float, Float>>()
            polygonBuffer.position(0)
            while (polygonBuffer.remaining() >= 2) polygon.add(polygonBuffer.get() to polygonBuffer.get())
            if (polygon.size < 3) return@forEach

            val minX = polygon.minOf { it.first }
            val maxX = polygon.maxOf { it.first }
            val minZ = polygon.minOf { it.second }
            val maxZ = polygon.maxOf { it.second }
            val localLines = ArrayList<Float>()

            // Polygon outline.
            for (i in polygon.indices) {
                val a = polygon[i]
                val b = polygon[(i + 1) % polygon.size]
                localLines.addAll(listOf(a.first, 0.006f, a.second, b.first, 0.006f, b.second))
            }

            fun inside(x: Float, z: Float): Boolean {
                var hit = false
                var j = polygon.lastIndex
                for (i in polygon.indices) {
                    val xi = polygon[i].first; val zi = polygon[i].second
                    val xj = polygon[j].first; val zj = polygon[j].second
                    if ((zi > z) != (zj > z) &&
                        x < (xj - xi) * (z - zi) / ((zj - zi).let { if (kotlin.math.abs(it) < 1e-6f) 1e-6f else it }) + xi) hit = !hit
                    j = i
                }
                return hit
            }

            // Clip each 10 cm grid line to the detected plane polygon.
            fun addClippedLine(vertical: Boolean, fixed: Float, start: Float, end: Float) {
                // Fewer clipping samples keep the grid renderer cheap without changing plane geometry.
                val samples = 40
                var previous: Pair<Float, Float>? = null
                for (step in 0..samples) {
                    val along = start + (end - start) * step / samples
                    val x = if (vertical) fixed else along
                    val z = if (vertical) along else fixed
                    val current = if (inside(x, z)) x to z else null
                    if (current != null && previous != null) {
                        localLines.addAll(listOf(previous.first, 0.008f, previous.second, current.first, 0.008f, current.second))
                    }
                    previous = current
                }
            }
            var x = kotlin.math.ceil(minX / 0.10f) * 0.10f
            while (x <= maxX) { addClippedLine(true, x, minZ, maxZ); x += 0.10f }
            var z = kotlin.math.ceil(minZ / 0.10f) * 0.10f
            while (z <= maxZ) { addClippedLine(false, z, minX, maxX); z += 0.10f }

            if (localLines.isEmpty()) return@forEach
            val localBuffer = floatBuffer(localLines.toFloatArray())
            val model = FloatArray(16)
            plane.centerPose.toMatrix(model, 0)
            val mvp = FloatArray(16)
            Matrix.multiplyMM(mvp, 0, viewProjection, 0, model, 0)
            GLES20.glUniformMatrix4fv(gridMvpHandle, 1, false, mvp, 0)
            val color = if (plane.type == Plane.Type.VERTICAL)
                floatArrayOf(0.25f, 0.78f, 1f, 0.85f)
            else floatArrayOf(0.2f, 1f, 0.65f, 0.85f)
            GLES20.glUniform4fv(gridColorHandle, 1, color, 0)
            GLES20.glVertexAttribPointer(gridPositionHandle, 3, GLES20.GL_FLOAT, false, 0, localBuffer)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, localLines.size / 3)
        }
        GLES20.glDisableVertexAttribArray(gridPositionHandle)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /**
     * Draw accumulated world-space samples in camera coordinates. Previously the scanner
     * collected thousands of depth samples but never rendered them, so the preview stayed
     * visually empty whenever ARCore had not yet classified a plane.
     */
    private fun drawDepthPointCloud(frame: Frame) {
        if (pointProgram == 0) return
        val worldPositions = synchronized(this) {
            val count = minOf(points.size, 15000)
            val packed = FloatArray(count * 3)
            var index = 0
            points.values.take(count).forEach { point ->
                packed[index++] = point[0]
                packed[index++] = point[1]
                packed[index++] = point[2]
            }
            packed
        }
        val offset = worldPositions.size
        if (offset == 0) return

        val view = FloatArray(16)
        val projection = FloatArray(16)
        val viewProjection = FloatArray(16)
        frame.camera.getViewMatrix(view, 0)
        frame.camera.getProjectionMatrix(projection, 0, 0.1f, 30f)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)

        val buffer = floatBuffer(worldPositions)
        GLES20.glUseProgram(pointProgram)
        GLES20.glUniformMatrix4fv(pointMvpHandle, 1, false, viewProjection, 0)
        GLES20.glEnableVertexAttribArray(pointPositionHandle)
        GLES20.glVertexAttribPointer(pointPositionHandle, 3, GLES20.GL_FLOAT, false, 0, buffer)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, offset / 3)
        GLES20.glDisableVertexAttribArray(pointPositionHandle)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /**
     * Draws accumulated depth triangles in ARCore world coordinates. This is a real
     * triangle surface reconstruction, not just a decorative point cloud.
     */
    private fun drawDepthMesh(frame: Frame) {
        val mesh = renderMeshVertices
        if (mesh.isEmpty() || gridProgram == 0) return
        val view = FloatArray(16)
        val projection = FloatArray(16)
        val viewProjection = FloatArray(16)
        frame.camera.getViewMatrix(view, 0)
        frame.camera.getProjectionMatrix(projection, 0, 0.1f, 30f)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)

        GLES20.glUseProgram(gridProgram)
        GLES20.glUniformMatrix4fv(gridMvpHandle, 1, false, viewProjection, 0)
        GLES20.glUniform4fv(gridColorHandle, 1, floatArrayOf(0.10f, 0.88f, 1f, 0.34f), 0)
        GLES20.glEnableVertexAttribArray(gridPositionHandle)
        val buffer = floatBuffer(mesh)
        GLES20.glVertexAttribPointer(gridPositionHandle, 3, GLES20.GL_FLOAT, false, 0, buffer)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.size / 3)
        GLES20.glDisableVertexAttribArray(gridPositionHandle)
        GLES20.glDisable(GLES20.GL_BLEND)
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
            // Prefer ARCore's dense depth map when the device supports the Depth API.
            // Unlike feature points, these samples carry metric distance in millimetres.
            collectDepthPoints(frame)

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

    private fun collectDepthPoints(frame: Frame) {
        val depthImage = try {
            frame.acquireDepthImage16Bits()
        } catch (_: Exception) {
            return
        }
        try {
            depthFrames++
            val width = depthImage.width
            val height = depthImage.height
            if (width <= 0 || height <= 0) return
            val imagePlane = depthImage.planes[0]
            val buffer = imagePlane.buffer
            val intrinsics = frame.camera.imageIntrinsics
            val cameraSize = intrinsics.imageDimensions
            val scaleX = width.toFloat() / cameraSize[0].coerceAtLeast(1)
            val scaleY = height.toFloat() / cameraSize[1].coerceAtLeast(1)
            val fx = intrinsics.focalLength[0] * scaleX
            val fy = intrinsics.focalLength[1] * scaleY
            val cx = intrinsics.principalPoint[0] * scaleX
            val cy = intrinsics.principalPoint[1] * scaleY
            if (fx <= 0f || fy <= 0f) return

            val cameraPose = frame.camera.pose

            // Persistent, spatially deduplicated metric points.
            val pointStride = 6
            synchronized(this) {
                var y = pointStride / 2
                while (y < height && points.size < maxScanPoints) {
                    var x = pointStride / 2
                    while (x < width && points.size < maxScanPoints) {
                        val offset = y * imagePlane.rowStride + x * imagePlane.pixelStride
                        if (offset + 1 < buffer.limit()) {
                            val packed = buffer.getShort(offset).toInt() and 0xFFFF
                            val depthMm = packed and 0x1FFF
                            val confidence = packed ushr 13
                            if (depthMm > 0 && confidence >= 1) {
                                val depth = depthMm / 1000f
                                val cameraPoint = floatArrayOf((x - cx) * depth / fx, -(y - cy) * depth / fy, -depth)
                                val world = cameraPose.transformPoint(cameraPoint)
                                val key = "${(world[0] * 20).toInt()}_${(world[1] * 20).toInt()}_${(world[2] * 20).toInt()}"
                                if (!points.containsKey(key)) points[key] = world
                            }
                        }
                        x += pointStride
                    }
                    y += pointStride
                }

                // Build a coarse triangle mesh from neighbouring depth samples. A cell is
                // accepted only when all four corners have valid depth and the depth jump
                // is small, preventing triangles from bridging object boundaries/empty space.
                val stride = 8
                val cols = (width + stride - 1) / stride
                val rows = (height + stride - 1) / stride
                val depthGrid = FloatArray(cols * rows)
                val worldGrid = arrayOfNulls<FloatArray>(cols * rows)
                for (gy in 0 until rows) {
                    val py = (gy * stride + stride / 2).coerceAtMost(height - 1)
                    for (gx in 0 until cols) {
                        val px = (gx * stride + stride / 2).coerceAtMost(width - 1)
                        val offset = py * imagePlane.rowStride + px * imagePlane.pixelStride
                        if (offset + 1 >= buffer.limit()) continue
                        val packed = buffer.getShort(offset).toInt() and 0xFFFF
                        val depthMm = packed and 0x1FFF
                        val confidence = packed ushr 13
                        if (depthMm == 0 || confidence < 1) continue
                        val depth = depthMm / 1000f
                        depthGrid[gy * cols + gx] = depth
                        worldGrid[gy * cols + gx] = cameraPose.transformPoint(
                            floatArrayOf((px - cx) * depth / fx, -(py - cy) * depth / fy, -depth)
                        )
                    }
                }

                var meshChanged = false
                fun addTriangle(a: FloatArray, b: FloatArray, d: FloatArray) {
                    val qa = "${(a[0] * 20).toInt()},${(a[1] * 20).toInt()},${(a[2] * 20).toInt()}"
                    val qb = "${(b[0] * 20).toInt()},${(b[1] * 20).toInt()},${(b[2] * 20).toInt()}"
                    val qd = "${(d[0] * 20).toInt()},${(d[1] * 20).toInt()},${(d[2] * 20).toInt()}"
                    val key = listOf(qa, qb, qd).sorted().joinToString("|")
                    if (!meshTriangles.containsKey(key) && meshTriangles.size < maxMeshTriangles) {
                        meshTriangles[key] = floatArrayOf(
                            a[0], a[1], a[2], b[0], b[1], b[2], d[0], d[1], d[2]
                        )
                        meshChanged = true
                    }
                }

                for (gy in 0 until rows - 1) {
                    for (gx in 0 until cols - 1) {
                        val i00 = gy * cols + gx
                        val i10 = i00 + 1
                        val i01 = i00 + cols
                        val i11 = i01 + 1
                        val p00 = worldGrid[i00] ?: continue
                        val p10 = worldGrid[i10] ?: continue
                        val p01 = worldGrid[i01] ?: continue
                        val p11 = worldGrid[i11] ?: continue
                        val d00 = depthGrid[i00]
                        val d10 = depthGrid[i10]
                        val d01 = depthGrid[i01]
                        val d11 = depthGrid[i11]
                        val minDepth = minOf(d00, d10, d01, d11)
                        val maxDepth = maxOf(d00, d10, d01, d11)
                        if (minDepth <= 0f || maxDepth - minDepth > maxOf(0.07f, minDepth * 0.035f)) continue
                        addTriangle(p00, p01, p10)
                        addTriangle(p10, p01, p11)
                    }
                }

                // Rebuild the GPU snapshot only when new triangles were actually added.
                // This prevents a large full-mesh allocation on every camera frame.
                if (meshChanged) {
                    val renderFloats = FloatArray(meshTriangles.size * 9)
                    var cursor = 0
                    meshTriangles.values.forEach { triangle ->
                        triangle.copyInto(renderFloats, cursor)
                        cursor += triangle.size
                    }
                    renderMeshVertices = renderFloats
                }
            }
        } catch (_: Exception) {
            // A transient depth-buffer issue must not stop the live scan.
        } finally {
            depthImage.close()
        }
    }

    private fun saveScan() {
        try {
            val snapshot = synchronized(this) {
                Triple(
                    points.values.map { it.copyOf() },
                    planes.values.map { JSONObject(it.toString()) },
                    meshTriangles.values.map { it.copyOf() }
                )
            }
            val pointArray = JSONArray()
            snapshot.first.forEach { pointArray.put(JSONArray(listOf(it[0], it[1], it[2]))) }
            val meshArray = JSONArray()
            snapshot.third.forEach { triangle ->
                meshArray.put(JSONArray(listOf(
                    JSONArray(listOf(triangle[0], triangle[1], triangle[2])),
                    JSONArray(listOf(triangle[3], triangle[4], triangle[5])),
                    JSONArray(listOf(triangle[6], triangle[7], triangle[8]))
                )))
            }
            val planeArray = JSONArray()
            snapshot.second.forEach { planeArray.put(it) }
            val result = JSONObject()
                .put("format", "vroom_spatial_scan_v1")
                .put("createdAt", System.currentTimeMillis())
                .put("tracking", "ARCore world coordinates")
                .put("planes", planeArray)
                .put("pointCloud", pointArray)
                .put("meshTriangles", meshArray)
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
            // Also export a standard Wavefront OBJ so the reconstructed triangle surface
            // can be opened in Blender, MeshLab and common 3D tools.
            val obj = StringBuilder("# VRoom depth reconstruction with coarse surface classes\n")
            obj.append("mtllib vroom_room_mesh.mtl\n")
            var floorFaces = 0
            var wallFaces = 0
            var otherFaces = 0
            snapshot.third.forEach { triangle ->
                obj.append("v ${triangle[0]} ${triangle[1]} ${triangle[2]}\n")
                obj.append("v ${triangle[3]} ${triangle[4]} ${triangle[5]}\n")
                obj.append("v ${triangle[6]} ${triangle[7]} ${triangle[8]}\n")
            }
            var activeMaterial = ""
            for (face in snapshot.third.indices) {
                val t = snapshot.third[face]
                val abx = t[3] - t[0]; val aby = t[4] - t[1]; val abz = t[5] - t[2]
                val acx = t[6] - t[0]; val acy = t[7] - t[1]; val acz = t[8] - t[2]
                val nx = aby * acz - abz * acy
                val ny = abz * acx - abx * acz
                val nz = abx * acy - aby * acx
                val length = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
                val verticality = kotlin.math.abs(ny / length)
                val material = when {
                    verticality > 0.82f -> "horizontal"
                    verticality < 0.25f -> "vertical"
                    else -> "other"
                }
                if (material != activeMaterial) {
                    obj.append("usemtl $material\n")
                    activeMaterial = material
                }
                when (material) {
                    "horizontal" -> floorFaces++
                    "vertical" -> wallFaces++
                    else -> otherFaces++
                }
                val base = face * 3 + 1
                obj.append("f $base ${base + 1} ${base + 2}\n")
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "vroom_room_mesh.obj")
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VRoom")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Could not create OBJ file in Downloads")
                contentResolver.openOutputStream(uri)?.use { it.write(obj.toString().toByteArray(Charsets.UTF_8)) }
                    ?: throw IllegalStateException("Could not write OBJ file")
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val mtl = """
                    newmtl horizontal
                    Kd 0.15 0.95 0.55
                    newmtl vertical
                    Kd 0.15 0.75 1.00
                    newmtl other
                    Kd 0.85 0.85 0.90
                """.trimIndent()
                val mtlValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "vroom_room_mesh.mtl")
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VRoom")
                }
                val mtlUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, mtlValues)
                    ?: throw IllegalStateException("Could not create MTL file")
                contentResolver.openOutputStream(mtlUri)?.use { it.write(mtl.toByteArray(Charsets.UTF_8)) }
            }
            stats.text = "Скан сохранён: JSON + OBJ · точек: ${snapshot.first.size} · треугольников: ${snapshot.third.size} · горизонтальных: $floorFaces · вертикальных: $wallFaces · прочих: $otherFaces"
        } catch (error: Exception) {
            stats.text = getString(R.string.room_scan_error, error.message ?: "")
        }
    }
}
