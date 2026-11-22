package dev.wifivr.client

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
        setPadding(0, dp(8), 0, dp(8))
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
        root.addView(TextView(this).apply {
            text = getString(R.string.room_title)
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.room_intro)
            textSize = 14f
            setTextColor(Color.rgb(159, 177, 205))
            setPadding(0, dp(8), 0, dp(18))
        })

        val profilePanel = panel()
        profilePanel.addView(label(getString(R.string.room_profile)))
        profile = EditText(this).apply {
            hint = getString(R.string.room_profile_hint)
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
        profilePanel.addView(profile, LinearLayout.LayoutParams(-1, dp(52)))
        root.addView(profilePanel)

        val scanPanel = panel()
        scanPanel.addView(label(getString(R.string.room_tracking_support)))
        capability = TextView(this).apply {
            text = getString(R.string.ar_checking)
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        scanPanel.addView(capability)
        scanPanel.addView(TextView(this).apply {
            text = getString(R.string.room_scan_not_ready)
            textSize = 13f
            setTextColor(Color.rgb(159, 177, 205))
            setPadding(0, dp(12), 0, 0)
        })
        root.addView(scanPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        val saveButton = Button(this).apply {
            text = getString(R.string.room_save)
            isAllCaps = false
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

    private fun loadProfile() {
        profile.setText(prefs.getString("last_profile", "default") ?: "default")
        saved.text = getString(R.string.room_profile) + ": " + profile.text.toString()
    }

    private fun saveProfile() {
        val name = profile.text.toString().trim().ifBlank { "default" }.take(32)
        prefs.edit().putString("last_profile", name).apply()
        saved.text = getString(R.string.room_saved_profile, name)
    }
}
