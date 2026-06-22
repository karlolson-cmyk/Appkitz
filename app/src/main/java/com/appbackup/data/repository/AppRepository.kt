package com.appbackup.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.appbackup.data.model.AppInfo
import java.io.File

class AppRepository(private val context: Context) {

    fun getInstalledApps(): List<AppInfo> {
        val pm = context.packageManager
        val apps = pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            .filter { info ->
                val appInfo = info.applicationInfo
                appInfo != null &&
                (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                appInfo.enabled
            }
            .mapNotNull { info ->
                val appInfo = info.applicationInfo ?: return@mapNotNull null
                val apkFile = File(appInfo.sourceDir)
                AppInfo(
                    name = pm.getApplicationLabel(appInfo).toString(),
                    packageName = appInfo.packageName,
                    apkPath = appInfo.sourceDir,
                    apkSize = apkFile.length(),
                    versionName = info.versionName ?: "",
                    versionCode = info.longVersionCode
                )
            }
            .sortedBy { it.name }
        return apps
    }
}
