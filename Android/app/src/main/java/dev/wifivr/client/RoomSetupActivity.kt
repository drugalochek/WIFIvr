package dev.wifivr.client

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.ar.core.ArCoreApk
import java.util.Locale

class RoomSetupActivity : Activity() {
    private lateinit var profile: EditText
    private lateinit var widthInput: EditText
    private lateinit var lengthInput: EditText
    private lateinit var capability: TextView
    private lateinit var saved: TextView

    private val prefs by lazy { getSharedPreferences("vroom_room_profiles", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(11, 16, 32)
        window.navigationBarColor = Color.rgb(11, 16, 32)
        buildUi()
        checkSpatialSupport()
        loadProfile()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = GradientDrawable().apply {
            setColor(Color.rgb(27, 39, 64))
            cornerRadius = dp(20).toFloat()
        }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.rgb(184, 204, 226))
        setPadding(0, dp(12), 0, dp(6))
    }

    private fun field(hintText: String) = EditText(this).apply {
        hint = hintText
        setSingleLine(true)
        textSize = 16f
        setTextColor(Color.WHITE)
        setHintTextColor(Color.rgb(140, 157, 183))
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            setColor(Color.rgb(17, 27, 47))
            cornerRadius = dp(12).toFloat()
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(11, 16, 32), Color.rgb(20, 29, 51))
            )
        }
        val scroll = ScrollView(this).apply { addView(root) }
        val title = TextView(this).apply {
            text = getString(R.string.room_title)
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        val intro = TextView(this).apply {
            text = getString(R.string.room_intro)
            textSize = 14f
            setTextColor(Color.rgb(159, 177, 205))
            setPadding(0, dp(8), 0, dp(18))
        }
        val profilePanel = panel()
        profilePanel.addView(label(getString(R.string.room_profile)))
        profile = field(getString(R.string.room_profile_hint))
        profilePanel.addView(profile, LinearLayout.LayoutParams(-1, dp(52)))
        root.addView(title)
        root.addView(intro)
        root.addView(profilePanel)

        val supportPanel = panel()
        supportPanel.addView(label(getString(R.string.room_tracking_support)))
        capability = TextView(this).apply {
            text = getString(R.string.ar_checking)
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        supportPanel.addView(capability)
        root.addView(supportPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        val areaPanel = panel()
        areaPanel.addView(label(getString(R.string.room_width)))
        widthInput = field("3.0")
        widthInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        areaPanel.addView(widthInput, LinearLayout.LayoutParams(-1, dp(52)))
        areaPanel.addView(label(getString(R.string.room_length)))
        lengthInput = field("3.0")
        lengthInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        areaPanel.addView(lengthInput, LinearLayout.LayoutParams(-1, dp(52)))
        areaPanel.addView(TextView(this).apply {
            text = getString(R.string.room_center)
            textSize = 12f
            setTextColor(Color.rgb(159, 177, 205))
            setPadding(0, dp(12), 0, 0)
        })
        root.addView(areaPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        val saveButton = Button(this).apply {
            text = getString(R.string.room_save)
            isAllCaps = false
            setTextColor(Color.rgb(8, 28, 28))
            background = GradientDrawable().apply {
                setColor(Color.rgb(73, 226, 178))
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener { saveProfile() }
        }
        root.addView(saveButton, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(16) })

        saved = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(184, 204, 226))
            setPadding(dp(4), dp(14), dp(4), 0)
        }
        root.addView(saved)
        setContentView(scroll)
    }

    private fun checkSpatialSupport() {
        try {
            val availability = ArCoreApk.getInstance().checkAvailability(this)
            capability.text = if (availability.isSupported) {
                getString(R.string.ar_supported)
            } else {
                getString(R.string.ar_unsupported)
            }
        } catch (error: Exception) {
            capability.text = getString(R.string.ar_error, error.message ?: "")
        }
    }

    private fun profileKey(): String {
        val raw = profile.text.toString().trim().ifBlank { "default" }
        return raw.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_-]"), "_")
            .take(32)
            .ifBlank { "default" }
    }

    private fun loadProfile() {
        val name = prefs.getString("last_profile", "default") ?: "default"
        profile.setText(name)
        val key = profileKey()
        val w = prefs.getFloat("$key.width", 3f)
        val l = prefs.getFloat("$key.length", 3f)
        widthInput.setText(String.format(Locale.US, "%.1f", w))
        lengthInput.setText(String.format(Locale.US, "%.1f", l))
        saved.text = getString(R.string.room_current, w, l) + "\n" +
            getString(R.string.position_not_active)
    }

    private fun saveProfile() {
        val w = widthInput.text.toString().replace(',', '.').toFloatOrNull()
        val l = lengthInput.text.toString().replace(',', '.').toFloatOrNull()
        if (w == null || l == null || w !in 1f..20f || l !in 1f..20f) {
            saved.text = getString(R.string.room_invalid)
            return
        }
        val name = profile.text.toString().trim().ifBlank { "default" }.take(32)
        val key = profileKey()
        prefs.edit()
            .putString("last_profile", name)
            .putFloat("$key.width", w)
            .putFloat("$key.length", l)
            .apply()
        saved.text = getString(R.string.room_saved, w, l) + "\n" +
            getString(R.string.position_not_active)
    }
}
