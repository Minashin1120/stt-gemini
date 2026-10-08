package com.minashin1120.voxcribe.task

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.minashin1120.voxcribe.VoxcribeApp

/** 通知アクションのActivity。マイク使用時の権限を表示中の画面で確認する。 */
class ToolbarActionActivity : ComponentActivity() {
    private var handled = false
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else {
            Toast.makeText(this, "マイクの使用が許可されていません", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(android.widget.TextView(this).apply {
            text = "Voxcribe 録音ツールバー"; setPadding(32, 32, 32, 32)
        })
    }

    override fun onPostResume() {
        super.onPostResume()
        if (handled) return
        handled = true
        if (intent.action == RecordingToolbar.COPY) {
            VoxcribeApp.instance.toolbar.copy()
            finish()
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        } else startRecording()
    }

    private fun startRecording() {
        val toolbar = VoxcribeApp.instance.toolbar
        if (toolbar.start()) {
            try { ToolbarService.update(this) } catch (_: Exception) {
                toolbar.cancel()
                Toast.makeText(this, "録音サービスを開始できませんでした", Toast.LENGTH_LONG).show()
            }
        }
        lifecycleScope.launch {
            while (toolbar.preparing) delay(50)
            finish()
        }
    }
}
