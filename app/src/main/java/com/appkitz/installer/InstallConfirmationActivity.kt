package com.appkitz.installer

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class InstallConfirmationActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            launchConfirmIntent()
        } else {
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchConfirmIntent()
    }

    private fun launchConfirmIntent() {
        val confirmIntent = readConfirmIntent(intent)
        if (confirmIntent == null) {
            Toast.makeText(this, "无法打开安装确认页面", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        try {
            launcher.launch(confirmIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot launch system install confirmation", e)
            Toast.makeText(
                this,
                "无法打开安装确认页面：${e.message ?: "未知错误"}",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    private fun readConfirmIntent(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
    }

    companion object {
        private const val TAG = "InstallConfirmationActivity"

        fun start(context: Context, confirmIntent: Intent) {
            val intent = Intent(context, InstallConfirmationActivity::class.java).apply {
                putExtra(Intent.EXTRA_INTENT, confirmIntent)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
