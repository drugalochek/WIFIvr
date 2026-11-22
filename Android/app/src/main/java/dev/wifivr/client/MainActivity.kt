package dev.wifivr.client

import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.Gravity
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
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        buildUi()
        status.text = if (rotationSensor == null) {
            "This phone has no rotation-vector sensor."
        } else {
            "Ready. Enter the PC's local IP address."
        }
        connectButton.isEnabled = rotationSensor != null
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }
        val title = TextView(this).apply {
            text = "WiFiVR"
            textSize = 30f
        }
        addressInput = EditText(this).apply {
            hint = "PC address, e.g. 192.168.1.10"
            setSingleLine(true)
            setText("192.168.1.100")
        }
        connectButton = Button(this).apply {
            text = "Connect"
            setOnClickListener { if (running) stopStreaming() else startStreaming() }
        }
        status = TextView(this).apply { textSize = 16f }
        root.addView(title)
        root.addView(addressInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(connectButton)
        root.addView(Button(this).apply { text = "Проверка рук и камеры"; setOnClickListener { startActivity(Intent(this@MainActivity, HandSetupActivity::class.java)) } })
        root.addView(status)
        setContentView(root)
    }

    private fun startStreaming() {
        val host = addressInput.text.toString().trim()
        val resolved = try { InetAddress.getByName(host) } catch (_: Exception) {
            status.text = "Invalid PC address."
            return
        }
        destination = resolved
        try {
            socket = DatagramSocket()
        } catch (e: Exception) {
            status.text = "Could not open UDP socket: ${e.message}"
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
            } catch (_: Exception) {
                // A transient Wi-Fi send failure should not kill the sensor callback.
            }
        }, 0, 16, TimeUnit.MILLISECONDS)
        rotationSensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        addressInput.isEnabled = false
        connectButton.text = "Disconnect"
        status.text = "Streaming head orientation to $host:9000"
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
        connectButton.text = "Connect"
        status.text = "Disconnected."
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running || event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val quaternion = FloatArray(4)
        SensorManager.getQuaternionFromVector(quaternion, event.values)
        // Android returns [w, x, y, z]; protocol uses [x, y, z, w].
        latestPose.set(floatArrayOf(quaternion[1], quaternion[2], quaternion[3], quaternion[0]))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }
}
