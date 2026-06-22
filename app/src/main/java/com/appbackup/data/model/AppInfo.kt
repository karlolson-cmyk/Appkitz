package com.appbackup.data.model

enum class AppType { USER, SYSTEM }

data class AppInfo(
    val name: String,
    val packageName: String,
    val apkPath: String,
    val apkSize: Long,
    val versionName: String,
    val versionCode: Long,
    val type: AppType = AppType.USER,
    val isSelected: Boolean = false
)
