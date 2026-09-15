package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.example.ui.VoiceChatScreen
import com.example.ui.VoiceChatViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: VoiceChatViewModel by viewModels()
    private var hasRecordAudioPermission by mutableStateOf(false)

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { micGranted ->
        hasRecordAudioPermission = micGranted
        viewModel.setMicPermissionGranted(micGranted)

        if (micGranted && viewModel.liveState.value == com.example.data.LiveState.IDLE) {
            viewModel.toggleLiveSession()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        viewModel.initContext(this)
        checkPermissions()

        setContent {
            MyApplicationTheme {
                VoiceChatScreen(
                    viewModel = viewModel,
                    hasRecordAudioPermission = hasRecordAudioPermission,
                    onRequestPermission = {
                        requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
    }

    private fun checkPermissions() {
        val micGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        hasRecordAudioPermission = micGranted
        viewModel.setMicPermissionGranted(micGranted)
    }
}
