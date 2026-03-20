package com.replyassistant.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.replyassistant.app.databinding.ActivityMainBinding
import com.replyassistant.app.settings.ReplyMode
import com.replyassistant.app.settings.SettingsRepository

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val settings by lazy { SettingsRepository(this) }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.editBaseUrl.setText(settings.baseUrl)
        binding.editToken.setText(settings.bearerToken)
        binding.switchUseNotifications.isChecked = settings.useNotifications
        binding.switchUseAccessibility.isChecked = settings.useAccessibility
        when (settings.replyMode) {
            ReplyMode.BRIEF -> binding.radioReplyMode.check(R.id.radioModeBrief)
            ReplyMode.PROFESSIONAL -> binding.radioReplyMode.check(R.id.radioModeProfessional)
            else -> binding.radioReplyMode.check(R.id.radioModeStandard)
        }

        // Persist immediately — these used to only apply on "Save", so toggles looked broken.
        binding.switchUseNotifications.setOnCheckedChangeListener { _, checked ->
            settings.useNotifications = checked
            updateStatus()
        }
        binding.switchUseAccessibility.setOnCheckedChangeListener { _, checked ->
            settings.useAccessibility = checked
            updateStatus()
        }

        binding.buttonSave.setOnClickListener {
            settings.baseUrl = binding.editBaseUrl.text?.toString().orEmpty()
            settings.bearerToken = binding.editToken.text?.toString().orEmpty()
            settings.useNotifications = binding.switchUseNotifications.isChecked
            settings.useAccessibility = binding.switchUseAccessibility.isChecked
            settings.replyMode = when (binding.radioReplyMode.checkedRadioButtonId) {
                R.id.radioModeBrief -> ReplyMode.BRIEF
                R.id.radioModeProfessional -> ReplyMode.PROFESSIONAL
                else -> ReplyMode.STANDARD
            }
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            updateStatus()
        }

        binding.buttonOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } else {
                Toast.makeText(this, "Overlay already allowed", Toast.LENGTH_SHORT).show()
            }
        }

        binding.buttonNotifications.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        binding.buttonAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        maybeRequestNotificationPermission()
        updateStatus()
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED -> {}
                ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) -> notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else -> notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val parts = mutableListOf<String>()
        parts += if (settings.isConfigured()) "Backend: configured" else "Backend: missing URL or token"
        parts += if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            "Overlay: allowed"
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            "Overlay: not allowed"
        } else {
            "Overlay: n/a"
        }
        parts += "Auto-suggest from notifications: ${if (settings.useNotifications) "on" else "off (use FAB in chat)"}"
        parts += "Accessibility + FAB: ${if (settings.useAccessibility) "on" else "off"}"
        parts += "Reply style: ${replyModeSummary(settings.replyMode)}"
        binding.textStatus.text = parts.joinToString("\n")
    }

    private fun replyModeSummary(mode: String): String = when (mode) {
        ReplyMode.BRIEF -> getString(R.string.reply_mode_brief)
        ReplyMode.PROFESSIONAL -> getString(R.string.reply_mode_professional)
        else -> getString(R.string.reply_mode_standard)
    }
}
