package dev.vroom.client

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class RoomSetupActivity : Activity() {
    private lateinit var profile: EditText
    private lateinit var saved: TextView
    private val prefs by lazy { getSharedPreferences("vroom_room_profiles", MODE_PRIVATE) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(11, 16, 32)
        window.navigationBarColor = Color.rgb(11, 16, 32)
        buildUi()
        loadProfile()
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }
    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(18))
        background = GradientDrawable().apply { setColor(Color.rgb(27, 39, 64)); cornerRadius = dp(20).toFloat() }
    }
    private fun label(value: String) = TextView(this).apply {
        text = value; textSize = 14f; setTextColor(Color.rgb(184, 204, 226)); setPadding(0, dp(8), 0, dp(8))
    }
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), statusBarHeight() + dp(20), dp(20), dp(24))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.rgb(11,16,32), Color.rgb(20,29,51)))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        root.addView(TextView(this).apply {
            text = getString(R.string.room_title); textSize = 28f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.room_intro); textSize = 14f; setTextColor(Color.rgb(159,177,205)); setPadding(0,dp(8),0,dp(18))
        })
        val profilePanel = panel()
        profilePanel.addView(label(getString(R.string.room_profile)))
        profile = EditText(this).apply {
            hint = getString(R.string.room_profile_hint); setSingleLine(true); textSize = 16f
            setTextColor(Color.WHITE); setHintTextColor(Color.rgb(140,157,183)); setPadding(dp(14),dp(12),dp(14),dp(12))
            background = GradientDrawable().apply { setColor(Color.rgb(17,27,47)); cornerRadius = dp(12).toFloat() }
        }
        profilePanel.addView(profile, LinearLayout.LayoutParams(-1,dp(52)))
        root.addView(profilePanel)
        val scanPanel = panel()
        scanPanel.addView(label(getString(R.string.room_tracking_support)))
        scanPanel.addView(TextView(this).apply {
            text = getString(R.string.room_scan_description); textSize = 13f; setTextColor(Color.rgb(159,177,205))
        })
        scanPanel.addView(Button(this).apply {
            text = getString(R.string.room_start_scan); isAllCaps = false
            setOnClickListener { startActivity(Intent(this@RoomSetupActivity, SpatialScanActivity::class.java)) }
        }, LinearLayout.LayoutParams(-1,dp(52)).apply { topMargin = dp(14) })
        root.addView(scanPanel, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(14) })
        root.addView(Button(this).apply {
            text = getString(R.string.room_save); isAllCaps = false; setOnClickListener { saveProfile() }
        }, LinearLayout.LayoutParams(-1,dp(54)).apply { topMargin = dp(16) })
        saved = TextView(this).apply { textSize = 13f; setTextColor(Color.rgb(184,204,226)); setPadding(dp(4),dp(14),dp(4),0) }
        root.addView(saved)
        setContentView(scroll)
    }
    private fun loadProfile() {
        profile.setText(prefs.getString("last_profile","default") ?: "default")
        saved.text = getString(R.string.room_profile) + ": " + profile.text
    }
    private fun saveProfile() {
        val name = profile.text.toString().trim().ifBlank { "default" }.take(32)
        prefs.edit().putString("last_profile",name).apply()
        saved.text = getString(R.string.room_saved_profile,name)
    }
}
