package com.appkitz.installer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = readConfirmIntent(intent)
                if (confirmIntent == null) {
                    toast(context, "无法打开安装确认页面")
                    InstallService.stop(context)
                    return
                }
                try {
                    InstallConfirmationActivity.start(context, confirmIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Cannot launch confirmation activity", e)
                    try {
                        confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(confirmIntent)
                    } catch (fallbackError: Exception) {
                        Log.e(TAG, "Cannot launch fallback confirm intent", fallbackError)
                        toast(context, "无法打开安装确认页面：${fallbackError.message ?: "未知错误"}")
                        InstallService.stop(context)
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                toast(context, "安装成功")
                InstallService.stop(context)
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                toast(context, "安装失败 [$status]: ${message ?: "未知错误"}")
                InstallService.stop(context)
            }
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

    private fun toast(context: Context, text: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val ACTION_INSTALL_RESULT = "com.appkitz.INSTALL_RESULT"
        private const val TAG = "InstallResultReceiver"
    }
}
