package dev.vroom.client

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.content.Intent
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MainActivity : Activity(), SensorEventListener {
    private lateinit var sensors: SensorManager
    private var rotationSensor: Sensor? = null
    private lateinit var addressInput: EditText
    private lateinit var status: TextView
    private lateinit var connectButton: Button
    private val latestPose = AtomicReference<FloatArray?>(null)
    private var sender: ScheduledExecutorService? = null
    @Volatile private var destination: InetAddress? = null
    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var sequence = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(11, 16, 32)
        window.navigationBarColor = Color.rgb(11, 16, 32)
        window.decorView.systemUiVisibility = 0
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        buildUi()
        status.text = getString(if (rotationSensor == null) R.string.no_rotation_sensor else R.string.ready_to_connect)
        connectButton.isEnabled = rotationSensor != null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), statusBarHeight() + dp(20), dp(24), dp(24))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(11, 16, 32), Color.rgb(20, 29, 51), Color.rgb(12, 20, 36))
            )
        }

        val brand = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 34f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val tagline = TextView(this).apply {
            text = getString(R.string.tagline)
            textSize = 14f
            setTextColor(Color.rgb(159, 177, 205))
            gravity = Gravity.CENTER
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(20))
            background = rounded(Color.rgb(27, 39, 64), 24)
            elevation = dp(8).toFloat()
        }
        val sectionTitle = TextView(this).apply {
            text = getString(R.string.connection_title)
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        val sectionHint = TextView(this).apply {
            text = getString(R.string.connection_hint)
            textSize = 13f
            setTextColor(Color.rgb(165, 181, 205))
            setPadding(0, dp(6), 0, dp(16))
        }
        addressInput = EditText(this).apply {
            hint = getString(R.string.pc_address_hint)
            setSingleLine(true)
            setText("192.168.1.100")
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(140, 157, 183))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.rgb(17, 27, 47), 14)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        connectButton = Button(this).apply {
            text = getString(R.string.connect)
            isAllCaps = false
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(8, 28, 28))
            background = rounded(Color.rgb(73, 226, 178), 16)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { if (running) stopStreaming() else startStreaming() }
        }
        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(177, 197, 219))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(14), dp(8), dp(4))
        }
        val handsButton = Button(this).apply {
            text = getString(R.string.hand_tracking)
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(48, 67, 99), 16)
            setOnClickListener { startActivity(Intent(this@MainActivity, HandSetupActivity::class.java).putExtra("pc_address", addressInput.text.toString().trim())) }
        }
        val roomButton = Button(this).apply {
            text = getString(R.string.room_setup)
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(48, 67, 99), 16)
            setOnClickListener { startActivity(Intent(this@MainActivity, RoomSetupActivity::class.java)) }
        }
        val displayButton = Button(this).apply {
            text = "Запустить VR-сеанс"
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(48, 67, 99), 16)
            setOnClickListener {
                if (running) stopStreaming()
                startActivity(Intent(this@MainActivity, VrSessionActivity::class.java)
                    .putExtra("pc_address", addressInput.text.toString().trim()))
            }
        }

        root.addView(brand, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(tagline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(30) })
        root.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(sectionTitle)
        panel.addView(sectionHint)
        panel.addView(addressInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        panel.addView(connectButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(14) })
        panel.addView(status)
        root.addView(handsButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(16) })
        root.addView(roomButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(10) })
        root.addView(displayButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(10) })
        setContentView(root)
    }

    private fun startStreaming() {
        val host = addressInput.text.toString().trim()
        val resolved = try { InetAddress.getByName(host) } catch (_: Exception) {
            status.text = getString(R.string.invalid_pc_address)
            return
        }
        destination = resolved
        try {
            socket = DatagramSocket()
        } catch (e: Exception) {
            status.text = getString(R.string.socket_error, e.message ?: "")
            return
        }
        running = true
        sequence = 0
        sender = Executors.newSingleThreadScheduledExecutor()
        sender?.scheduleAtFixedRate({
            val pose = latestPose.get() ?: return@scheduleAtFixedRate
            val target = destination ?: return@scheduleAtFixedRate
            try {
                val json = JSONObject()
                    .put("type", "head_pose")
                    .put("version", 1)
                    .put("timestampNs", System.nanoTime())
                    .put("sequence", sequence++)
                    .put("orientation", JSONObject()
                        .put("x", pose[0].toDouble())
                        .put("y", pose[1].toDouble())
                        .put("z", pose[2].toDouble())
                        .put("w", pose[3].toDouble()))
                    .toString()
                val bytes = json.toByteArray(Charsets.UTF_8)
                socket?.send(DatagramPacket(bytes, bytes.size, target, 9000))
            } catch (_: Exception) { }
        }, 0, 16, TimeUnit.MILLISECONDS)
        rotationSensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        addressInput.isEnabled = false
        connectButton.text = getString(R.string.disconnect)
        status.text = getString(R.string.streaming_to, host)
    }

    private fun stopStreaming() {
        running = false
        sensors.unregisterListener(this)
        sender?.shutdownNow()
        sender = null
        socket?.close()
        socket = null
        destination = null
        addressInput.isEnabled = true
        connectButton.text = getString(R.string.connect)
        status.text = getString(R.string.disconnected)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running || event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val quaternion = FloatArray(4)
        SensorManager.getQuaternionFromVector(quaternion, event.values)
        latestPose.set(floatArrayOf(quaternion[1], quaternion[2], quaternion[3], quaternion[0]))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }
}
