package com.wordtaker.keyboard.wordtaker.speech

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * 透明的权限请求中转 Activity。
 *
 * 输入法（IME）是 Service，无法在运行时申请权限。当键盘麦克风首次需要
 * RECORD_AUDIO 权限时，由 IME 启动本 Activity 来弹出系统权限对话框；
 * 无论用户是否授权，处理完后立即 finish()，对用户几乎无感。
 */
class MicPermissionActivity : ComponentActivity() {

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 无论授予与否都直接结束：用户回到键盘后再次点击麦克风即可。
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val alreadyGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (alreadyGranted) {
            finish()
            return
        }

        requestPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun finish() {
        super.finish()
        // 无过渡动画，保持隐形体验。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
