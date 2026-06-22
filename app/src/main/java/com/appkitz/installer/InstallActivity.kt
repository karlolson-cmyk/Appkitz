package com.appkitz.installer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.appkitz.ui.theme.AppkitzTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class InstallActivity : ComponentActivity() {

    companion object {
        const val ACTION_INSTALL_RESULT = "com.appkitz.INSTALL_RESULT"
    }

    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) return
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            val toast = if (status == PackageInstaller.STATUS_SUCCESS) {
                "安装成功"
            } else {
                "安装失败 [${status}]: ${message ?: "未知错误"}"
            }
            Toast.makeText(context, toast, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerReceiver(installReceiver, IntentFilter(ACTION_INSTALL_RESULT), if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RECEIVER_NOT_EXPORTED else 0)

        val uri = when (intent?.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> intent?.data
        } ?: run {
            Toast.makeText(this, "未指定文件", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            AppkitzTheme {
                InstallScreen(uri = uri, onDone = { finish() })
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(installReceiver) } catch (_: Exception) {}
    }
}

@Composable
fun InstallScreen(uri: Uri, onDone: () -> Unit) {
    val context = LocalContext.current
    var cacheFile by remember { mutableStateOf<File?>(null) }
    var entries by remember { mutableStateOf<List<SplitEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf(false) }
    var installError by remember { mutableStateOf<String?>(null) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                val ext = uri.pathSegments.lastOrNull()?.substringAfterLast('.', "") ?: ""
                val cacheName = "install_${System.nanoTime()}" + if (ext.isNotEmpty()) ".$ext" else ""
                val file = File(context.cacheDir, cacheName)
                val inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    error = "无法读取文件"
                    return@withContext
                }
                inputStream.use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
                if (file.length() == 0L) {
                    file.delete()
                    error = "文件为空"
                    return@withContext
                }
                cacheFile = file
                entries = if (SplitApkParser.isSplitPackage(file)) {
                    SplitApkParser.parse(file)
                } else {
                    listOf(SplitEntry(file.name, "base", "基础 APK", isRequired = true, isDefault = true))
                }
            } catch (e: Exception) {
                error = e.message ?: "解析失败"
            }
        }
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = onDone,
            title = { Text("需要权限") },
            text = { Text("安装应用需要开启「安装未知应用」权限。\n请前往：设置 → 特殊权限 → 安装未知应用 → Appkitz → 允许") },
            confirmButton = { TextButton(onClick = {
                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).also {
                    it.data = android.net.Uri.parse("package:${context.packageName}")
                    context.startActivity(it)
                }
                showPermissionDialog = false
            }) { Text("去设置") } },
            dismissButton = { TextButton(onClick = { showPermissionDialog = false; onDone() }) { Text("取消") } }
        )
        return
    }

    installError?.let {
        AlertDialog(
            onDismissRequest = onDone,
            title = { Text("安装失败") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = onDone) { Text("确定") } }
        )
        return
    }

    error?.let {
        AlertDialog(
            onDismissRequest = onDone,
            title = { Text("错误") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = onDone) { Text("确定") } }
        )
        return
    }

    entries?.let { apkEntries ->
        if (!installing) {
            InstallDialog(
                entries = apkEntries,
                onInstall = { selected ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                        showPermissionDialog = true
                        return@InstallDialog
                    }
                    installing = true
                    val file = cacheFile
                    if (file == null) {
                        installError = "文件不存在"
                        installing = false
                        return@InstallDialog
                    }
                    scope.launch(Dispatchers.IO) {
                        installApks(context, file, selected) { msg ->
                            installError = msg
                            installing = false
                        }
                    }
                },
                onDismiss = onDone
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("正在安装...")
                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(onClick = onDone) { Text("取消") }
                }
            }
        }
    } ?: run {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@Composable
fun InstallDialog(
    entries: List<SplitEntry>,
    onInstall: (List<SplitEntry>) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val defaults = remember(entries) { computeSmartDefaults(context, entries) }
    var selected by remember { mutableStateOf(defaults) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择安装选项") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                entries.forEach { entry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        Checkbox(
                            checked = entry in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + entry else selected - entry
                            },
                            enabled = !entry.isRequired
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(entry.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onInstall(selected.toList()) }) { Text("安装") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private const val DENSITY_UNKNOWN = Integer.MAX_VALUE
private val DENSITY_VALUES = mapOf(
    "ldpi" to 120, "mdpi" to 160, "hdpi" to 240, "xhdpi" to 320,
    "xxhdpi" to 480, "xxxhdpi" to 640, "tvdpi" to 213
)

private fun computeSmartDefaults(context: Context, entries: List<SplitEntry>): Set<SplitEntry> {
    val result = mutableSetOf<SplitEntry>()
    val deviceAbis = Build.SUPPORTED_ABIS.toSet()
    val deviceDensity = context.resources.displayMetrics.densityDpi
    val deviceLocale = Locale.getDefault().language

    var bestAbi: SplitEntry? = null
    var bestDensity: SplitEntry? = null
    var bestDensityDiff = DENSITY_UNKNOWN
    var bestLocale: SplitEntry? = null
    var seenFeature = mutableSetOf<String?>()

    for (entry in entries) {
        if (entry.isRequired) {
            result.add(entry)
            seenFeature.add(entry.type)
            continue
        }

        when (entry.type) {
            "base" -> {
                result.add(entry)
                seenFeature.add(entry.type)
            }
            "abi" -> {
                val match = deviceAbis.any { abi ->
                    entry.label.contains(abi, ignoreCase = true)
                }
                if (match && (bestAbi == null)) {
                    bestAbi = entry
                }
            }
            "density" -> {
                val entryDensity = DENSITY_VALUES.entries.find { d ->
                    entry.label.contains(d.key, ignoreCase = true)
                }?.value ?: 0
                if (entryDensity > 0) {
                    val diff = kotlin.math.abs(entryDensity - deviceDensity)
                    if (diff < bestDensityDiff) {
                        bestDensityDiff = diff
                        bestDensity = entry
                    }
                }
            }
            "locale" -> {
                if (entry.label.startsWith(deviceLocale, ignoreCase = true)) {
                    bestLocale = entry
                }
            }
            else -> {
                if (entry.type !in seenFeature) {
                    result.add(entry)
                    seenFeature.add(entry.type)
                }
            }
        }
    }

    bestAbi?.let { result.add(it) }
    bestDensity?.let { result.add(it) }
    bestLocale?.let { result.add(it) }

    return result
}

private fun installApks(context: Context, file: File, selected: List<SplitEntry>, onError: (String) -> Unit) {
    if (!file.exists()) {
        onError("文件不存在")
        return
    }

    var session: PackageInstaller.Session? = null
    try {
        val packageInstaller = context.packageManager.packageInstaller
        val sessionParams = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)

        val isSingleApk = selected.size == 1 && selected[0].fileName == file.name

        val baseEntry = selected.find { it.type == "base" }
        if (baseEntry != null) {
            if (isSingleApk) {
                val pkgInfo = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                if (pkgInfo != null) {
                    sessionParams.setAppPackageName(pkgInfo.packageName)
                }
            } else {
                var found = false
                ZipFile(file).use { zip ->
                    val ze = findZipEntry(zip, baseEntry.fileName)
                    if (ze != null) {
                        val tempBase = File(context.cacheDir, "tmp_base_${System.nanoTime()}.apk")
                        try {
                            zip.getInputStream(ze).use { input ->
                                FileOutputStream(tempBase).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            val pkgInfo = context.packageManager.getPackageArchiveInfo(tempBase.absolutePath, 0)
                            if (pkgInfo != null) {
                                sessionParams.setAppPackageName(pkgInfo.packageName)
                                found = true
                            }
                        } finally {
                            tempBase.delete()
                        }
                    }
                    if (!found) {
                        val entries = zip.entries()
                        while (entries.hasMoreElements()) {
                            val e = entries.nextElement()
                            if (e.isDirectory) continue
                            val name = e.name.substringAfterLast('/')
                            if (!name.endsWith(".apk")) continue
                            val tempBase = File(context.cacheDir, "tmp_base_${System.nanoTime()}.apk")
                            try {
                                zip.getInputStream(e).use { input ->
                                    FileOutputStream(tempBase).use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                val pkgInfo = context.packageManager.getPackageArchiveInfo(tempBase.absolutePath, 0)
                                if (pkgInfo != null) {
                                    sessionParams.setAppPackageName(pkgInfo.packageName)
                                    break
                                }
                            } finally {
                                tempBase.delete()
                            }
                        }
                    }
                }
            }
        }

        val sessionId = packageInstaller.createSession(sessionParams)
        session = packageInstaller.openSession(sessionId)

        if (isSingleApk) {
            file.inputStream().use { input ->
                session.openWrite(file.name, 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
        } else {
            ZipFile(file).use { zip ->
                for (entry in selected) {
                    val ze = findZipEntry(zip, entry.fileName) ?: continue
                    var actualSize = ze.size
                    if (actualSize < 0) {
                        val tempFile = File(context.cacheDir, "tmp_${System.nanoTime()}")
                        try {
                            zip.getInputStream(ze).use { input ->
                                FileOutputStream(tempFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            actualSize = tempFile.length()
                            tempFile.inputStream().use { input ->
                                session.openWrite(entry.fileName, 0, actualSize).use { out ->
                                    input.copyTo(out)
                                    session.fsync(out)
                                }
                            }
                        } finally {
                            tempFile.delete()
                        }
                    } else {
                        zip.getInputStream(ze).use { input ->
                            session.openWrite(entry.fileName, 0, actualSize).use { out ->
                                input.copyTo(out)
                                session.fsync(out)
                            }
                        }
                    }
                }
            }
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context, sessionId,
            Intent(InstallActivity.ACTION_INSTALL_RESULT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session.commit(pendingIntent.intentSender)
    } catch (e: Exception) {
        try { session?.abandon() } catch (_: Exception) {}
        val errMsg = e.message
        onError(if (errMsg != null) "${e::class.simpleName}: $errMsg" else "${e::class.simpleName}")
    }
}

private fun findZipEntry(zip: ZipFile, fileName: String): ZipEntry? {
    zip.getEntry(fileName)?.let { return it }
    val entries = zip.entries()
    while (entries.hasMoreElements()) {
        val e = entries.nextElement()
        if (e.isDirectory) continue
        if (e.name.endsWith("/$fileName") || e.name == fileName) {
            return e
        }
    }
    return null
}