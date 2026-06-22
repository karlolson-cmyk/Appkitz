package com.appkitz.installer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.Serializable
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class InstallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("正在安装..."))

        val file = intent?.getStringExtra(EXTRA_FILE_PATH)?.let { File(it) }
        if (file == null || !file.exists()) {
            notifyFailure("安装文件不存在")
            stopSelfSafely()
            return START_NOT_STICKY
        }

        val selected = readSelectedEntries(intent)
        scope.launch {
            installApks(file, selected)
        }
        return START_NOT_STICKY
    }

    private fun readSelectedEntries(intent: Intent): List<SplitEntry> {
        @Suppress("DEPRECATION")
        val holder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_SELECTED, SplitEntriesHolder::class.java)
        } else {
            intent.getSerializableExtra(EXTRA_SELECTED) as? SplitEntriesHolder
        }
        return holder?.entries.orEmpty()
    }

    private fun installApks(file: File, selected: List<SplitEntry>) {
        var session: PackageInstaller.Session? = null
        try {
            if (selected.isEmpty()) {
                notifyFailure("请选择至少一个 APK")
                return
            }

            val packageInstaller = packageManager.packageInstaller
            val sessionParams =
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            sessionParams.setInstallerPackageName(packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                sessionParams.setRequireUserAction(
                    PackageInstaller.SessionParams.USER_ACTION_REQUIRED
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                sessionParams.setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
            }

            val isSingleApk = selected.size == 1 && selected[0].fileName == file.name
            configureSessionSize(sessionParams, file, selected, isSingleApk)
            configurePackageName(sessionParams, file, selected, isSingleApk)

            val sessionId = packageInstaller.createSession(sessionParams)
            session = packageInstaller.openSession(sessionId)
            writeSelectedApks(session, file, selected, isSingleApk)

            val resultIntent = Intent(InstallResultReceiver.ACTION_INSTALL_RESULT).apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                sessionId,
                resultIntent,
                pendingIntentFlags
            )
            session.commit(pendingIntent.intentSender)
        } catch (e: Throwable) {
            Log.e(TAG, "installApks failed", e)
            try {
                session?.abandon()
            } catch (_: Exception) {
            }
            val message = e.message
            notifyFailure(
                if (message != null) "${e::class.java.simpleName}: $message"
                else e::class.java.simpleName
            )
            stopSelfSafely()
        } finally {
            file.delete()
        }
    }

    private fun configureSessionSize(
        sessionParams: PackageInstaller.SessionParams,
        file: File,
        selected: List<SplitEntry>,
        isSingleApk: Boolean
    ) {
        if (isSingleApk) {
            sessionParams.setSize(file.length())
            return
        }

        ZipFile(file).use { zip ->
            val missingEntry = selected.firstOrNull { findZipEntry(zip, it.fileName) == null }
            if (missingEntry != null) {
                throw IllegalStateException("安装包中缺少 ${missingEntry.fileName}")
            }
            var totalSize = 0L
            var allSizesKnown = true
            for (entry in selected) {
                val size = findZipEntry(zip, entry.fileName)?.size ?: -1L
                if (size <= 0L) {
                    allSizesKnown = false
                    break
                }
                totalSize += size
            }
            if (allSizesKnown && totalSize > 0L) {
                sessionParams.setSize(totalSize)
            }
        }
    }

    private fun configurePackageName(
        sessionParams: PackageInstaller.SessionParams,
        file: File,
        selected: List<SplitEntry>,
        isSingleApk: Boolean
    ) {
        val baseEntry = selected.find { it.type == "base" } ?: return
        if (isSingleApk) {
            packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.let {
                sessionParams.setAppPackageName(it.packageName)
            }
            return
        }

        ZipFile(file).use { zip ->
            findZipEntry(zip, baseEntry.fileName)?.let { base ->
                readPackageNameFromZipEntry(zip, base)?.let {
                    sessionParams.setAppPackageName(it)
                    return
                }
            }

            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                if (!entry.name.substringAfterLast('/').endsWith(".apk", ignoreCase = true)) {
                    continue
                }
                readPackageNameFromZipEntry(zip, entry)?.let {
                    sessionParams.setAppPackageName(it)
                    return
                }
            }
        }
    }

    private fun readPackageNameFromZipEntry(zip: ZipFile, entry: ZipEntry): String? {
        val tempBase = File(cacheDir, "tmp_base_${System.nanoTime()}.apk")
        return try {
            zip.getInputStream(entry).use { input ->
                FileOutputStream(tempBase).use { output ->
                    input.copyTo(output)
                }
            }
            packageManager.getPackageArchiveInfo(tempBase.absolutePath, 0)?.packageName
        } finally {
            tempBase.delete()
        }
    }

    private fun writeSelectedApks(
        session: PackageInstaller.Session,
        file: File,
        selected: List<SplitEntry>,
        isSingleApk: Boolean
    ) {
        if (isSingleApk) {
            file.inputStream().use { input ->
                session.openWrite(file.name, 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            return
        }

        ZipFile(file).use { zip ->
            val usedNames = mutableSetOf<String>()
            for (entry in selected) {
                val zipEntry = findZipEntry(zip, entry.fileName)
                    ?: throw IllegalStateException("安装包中缺少 ${entry.fileName}")
                val sessionName = buildSessionFileName(entry.fileName, usedNames)
                writeZipEntryToSession(session, zip, zipEntry, sessionName)
            }
        }
    }

    private fun writeZipEntryToSession(
        session: PackageInstaller.Session,
        zip: ZipFile,
        zipEntry: ZipEntry,
        sessionName: String
    ) {
        var actualSize = zipEntry.size
        if (actualSize >= 0) {
            zip.getInputStream(zipEntry).use { input ->
                session.openWrite(sessionName, 0, actualSize).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            return
        }

        val tempFile = File(cacheDir, "tmp_${System.nanoTime()}.apk")
        try {
            zip.getInputStream(zipEntry).use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            actualSize = tempFile.length()
            tempFile.inputStream().use { input ->
                session.openWrite(sessionName, 0, actualSize).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
        } finally {
            tempFile.delete()
        }
    }

    private fun notifyFailure(message: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            NOTIFICATION_ID + 1,
            buildNotification("安装失败：$message", error = true)
        )
    }

    private fun buildNotification(text: String, error: Boolean = false): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(CHANNEL_ID) == null
        ) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "应用安装",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Appkitz 安装")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(!error)
            .setSilent(!error)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun stopSelfSafely() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        private const val TAG = "InstallService"
        private const val CHANNEL_ID = "appkitz_install"
        private const val NOTIFICATION_ID = 4271

        private const val EXTRA_FILE_PATH = "extra_file_path"
        private const val EXTRA_SELECTED = "extra_selected"

        fun start(context: Context, file: File, selected: List<SplitEntry>) {
            val intent = Intent(context, InstallService::class.java).apply {
                putExtra(EXTRA_FILE_PATH, file.absolutePath)
                putExtra(EXTRA_SELECTED, SplitEntriesHolder(ArrayList(selected)))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, InstallService::class.java))
        }
    }
}

private class SplitEntriesHolder(
    val entries: ArrayList<SplitEntry>
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

private fun buildSessionFileName(
    fileName: String,
    usedNames: MutableSet<String>
): String {
    val cleanName = fileName.substringAfterLast('/').ifBlank { "split.apk" }
    if (usedNames.add(cleanName)) return cleanName

    val baseName = cleanName.substringBeforeLast('.', cleanName)
    val extension = cleanName.substringAfterLast('.', "")
    var index = 1
    while (true) {
        val candidate = if (extension.isEmpty()) {
            "${baseName}_$index"
        } else {
            "${baseName}_$index.$extension"
        }
        if (usedNames.add(candidate)) return candidate
        index++
    }
}

private fun findZipEntry(zip: ZipFile, fileName: String): ZipEntry? {
    zip.getEntry(fileName)?.let { return it }
    val entries = zip.entries()
    while (entries.hasMoreElements()) {
        val entry = entries.nextElement()
        if (entry.isDirectory) continue
        if (entry.name == fileName || entry.name.endsWith("/$fileName")) {
            return entry
        }
    }
    return null
}
