package com.appbackup.data.model

data class AppInfo(
    val name: String,
    val packageName: String,
    val apkPath: String,
    val apkSize: Long,
    val versionName: String,
    val versionCode: Long,
    val isSelected: Boolean = false
)
