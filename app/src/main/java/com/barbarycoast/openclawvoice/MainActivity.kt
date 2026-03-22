package com.barbarycoast.openclawvoice

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.barbarycoast.openclawvoice.ui.EnrollmentDialog
import com.barbarycoast.openclawvoice.ui.WaveformView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val viewModel: VoiceViewModel by viewModels()

    private lateinit var waveformView: WaveformView
    private lateinit var speakerNameView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var avatarStrip: LinearLayout

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onPermissionGranted()
        } else {
            showPermissionRationale()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge immersive
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContentView(R.layout.activity_main)

        waveformView = findViewById(R.id.waveformView)
        speakerNameView = findViewById(R.id.speakerName)
        statusTextView = findViewById(R.id.statusText)
        avatarStrip = findViewById(R.id.avatarStrip)

        observeViewModel()
        checkPermission()
    }

    private fun checkPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED -> onPermissionGranted()

            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ->
                showPermissionRationale()

            else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun showPermissionRationale() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_title)
            .setMessage(R.string.permission_message)
            .setPositiveButton(R.string.permission_grant) { _, _ ->
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            .setCancelable(false)
            .show()
    }

    private fun onPermissionGranted() {
        lifecycleScope.launch {
            viewModel.loadProfiles()
            if (viewModel.needsEnrollment.value) {
                showEnrollmentDialog()
            } else {
                viewModel.startVoiceLoop()
            }
        }
    }

    private fun showEnrollmentDialog() {
        EnrollmentDialog(
            context = this,
            speakerEngine = viewModel.speakerEngine,
            onComplete = { name ->
                viewModel.onEnrollmentComplete()
                viewModel.startVoiceLoop()
            },
            onCancel = {
                // Start anyway even without enrollment
                viewModel.onEnrollmentComplete()
                viewModel.startVoiceLoop()
            }
        ).show()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        when (state) {
                            VoiceViewModel.State.IDLE -> {
                                statusTextView.text = ""
                                waveformView.setMode(WaveformView.Mode.IDLE)
                            }
                            VoiceViewModel.State.LISTENING -> {
                                statusTextView.text = getString(R.string.listening)
                                waveformView.setMode(WaveformView.Mode.LISTENING)
                            }
                            VoiceViewModel.State.THINKING -> {
                                statusTextView.text = getString(R.string.thinking)
                                waveformView.setMode(WaveformView.Mode.IDLE)
                                waveformView.setAmplitude(0.3f) // subtle pulse
                            }
                            VoiceViewModel.State.SPEAKING -> {
                                statusTextView.text = getString(R.string.speaking)
                                waveformView.setMode(WaveformView.Mode.SPEAKING)
                            }
                        }
                    }
                }

                launch {
                    viewModel.speakerName.collect { name ->
                        speakerNameView.text = name ?: ""
                        speakerNameView.visibility = if (name != null) View.VISIBLE else View.INVISIBLE
                    }
                }

                launch {
                    viewModel.amplitude.collect { amp ->
                        waveformView.setAmplitude(amp)
                    }
                }

                launch {
                    viewModel.profiles.collect { profiles ->
                        buildAvatarStrip(profiles)
                    }
                }

                launch {
                    viewModel.needsEnrollment.collect { needs ->
                        if (needs && ContextCompat.checkSelfPermission(
                                this@MainActivity, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            showEnrollmentDialog()
                        }
                    }
                }
            }
        }
    }

    private fun buildAvatarStrip(profiles: List<com.barbarycoast.openclawvoice.db.SpeakerProfile>) {
        avatarStrip.removeAllViews()

        for (profile in profiles) {
            val avatar = createAvatarView(profile.name)
            avatar.setOnLongClickListener { view ->
                showAvatarMenu(view, profile)
                true
            }
            avatarStrip.addView(avatar)
        }
    }

    private fun createAvatarView(name: String): TextView {
        val sizeDp = 48
        val sizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, sizeDp.toFloat(), resources.displayMetrics
        ).toInt()
        val marginPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics
        ).toInt()

        return TextView(this).apply {
            val initials = name.split(" ")
                .take(2)
                .joinToString("") { it.first().uppercase() }
                .ifEmpty { "?" }

            text = initials
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#FFB300"))

            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                setMargins(marginPx, 0, marginPx, 0)
            }

            // Make it circular using clip
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
        }
    }

    private fun showAvatarMenu(anchor: View, profile: com.barbarycoast.openclawvoice.db.SpeakerProfile) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, getString(R.string.add_new_voice))
        popup.menu.add(0, 2, 1, getString(R.string.delete_speaker, profile.name))

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    viewModel.stopVoiceLoop()
                    showEnrollmentDialog()
                    true
                }
                2 -> {
                    lifecycleScope.launch {
                        viewModel.deleteProfile(profile.id)
                    }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    override fun onResume() {
        super.onResume()
        if (viewModel.state.value != VoiceViewModel.State.IDLE &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            // Resume if we were running
        }
    }

    override fun onPause() {
        super.onPause()
        // Don't stop the loop on pause — keep running in background
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.stopVoiceLoop()
    }
}
