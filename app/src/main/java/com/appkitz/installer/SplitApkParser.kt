package com.appkitz.installer

import android.os.Build
import java.io.File
import java.io.Serializable
import java.util.zip.ZipFile

data class SplitEntry(
    val fileName: String,
    val type: String,
    val label: String,
    val isRequired: Boolean = false,
    val isDefault: Boolean = false
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

object SplitApkParser {

    private val DEVICE_ABIS = Build.SUPPORTED_ABIS.toSet()
    private val DENSITY_MAP = mapOf(
        "ldpi" to 120, "mdpi" to 160, "hdpi" to 240, "xhdpi" to 320,
        "xxhdpi" to 480, "xxxhdpi" to 640, "tvdpi" to 213
    )
    private val ABI_MAP = mapOf(
        "arm64_v8a" to "arm64-v8a", "arm64-v8a" to "arm64-v8a",
        "armeabi_v7a" to "armeabi-v7a", "armeabi-v7a" to "armeabi-v7a",
        "arm64" to "arm64-v8a", "armeabi" to "armeabi-v7a",
        "x86_64" to "x86_64", "x86" to "x86"
    )

    fun parse(file: File): List<SplitEntry> {
        ZipFile(file).use { zip ->
            return parseByNaming(zip)
        }
    }

    fun isSplitPackage(file: File): Boolean {
        if (!file.canRead()) return false
        try {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    if (entry.name.substringAfterLast('/').endsWith(".apk", ignoreCase = true)) {
                        return true
                    }
                }
                return false
            }
        } catch (_: Exception) {
            return false
        }
    }

    private fun parseByNaming(zip: ZipFile): List<SplitEntry> {
        val result = mutableListOf<SplitEntry>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            val path = entry.name
            val name = path.substringAfterLast('/')
            if (!name.endsWith(".apk", ignoreCase = true)) continue

            val lower = name.lowercase()
            when {
                lower == "base.apk" || lower.startsWith("base") ->
                    result.add(SplitEntry(path, "base", "基础 APK", isRequired = true, isDefault = true))
                lower.startsWith("split_config.") || lower.startsWith("config.") -> {
                    val configPart = name.substringAfter("config.").substringBefore(".apk")
                    parseSplitConfig(configPart, path)?.let { result.add(it) }
                }
                lower.contains("arm64") || lower.contains("armv8") ->
                    result.add(SplitEntry(path, "abi", "arm64-v8a 代码", isDefault = DEVICE_ABIS.any { it.contains("arm64") }))
                lower.contains("armeabi") || lower.contains("armv7") ->
                    result.add(SplitEntry(path, "abi", "armeabi-v7a 代码", isDefault = DEVICE_ABIS.any { it.contains("armeabi") }))
                lower.contains("x86_64") ->
                    result.add(SplitEntry(path, "abi", "x86_64 代码", isDefault = DEVICE_ABIS.any { it.contains("x86_64") }))
                lower.contains("x86") && !lower.contains("x86_64") ->
                    result.add(SplitEntry(path, "abi", "x86 代码", isDefault = DEVICE_ABIS.any { it.contains("x86") }))
                lower.contains("dpi") || lower.contains("ldpi") || lower.contains("mdpi") || lower.contains("hdpi") ->
                    result.add(SplitEntry(path, "density", name, isDefault = true))
                lower.matches(Regex(".*[a-z]{2}(-[a-zA-Z]{2})?\\.apk")) && lower.length < 20 ->
                    result.add(SplitEntry(path, "locale", "${name.substringBeforeLast('.')} 语言", isDefault = true))
                else -> result.add(SplitEntry(path, "unknown", name, isDefault = true))
            }
        }
        if (result.none { it.type == "base" } && result.any { it.type != "base" }) {
            val firstNonConfig = result.find { e ->
                val fn = e.fileName.substringAfterLast('/')
                !fn.startsWith("split_config.") && !fn.startsWith("config.") && !e.type.startsWith("abi") && !e.type.startsWith("density") && !e.type.startsWith("locale")
            }
            if (firstNonConfig != null) {
                result.remove(firstNonConfig)
                result.add(0, firstNonConfig.copy(type = "base", label = "基础 APK", isRequired = true, isDefault = true))
            } else {
                val first = result.first()
                result.remove(first)
                result.add(0, first.copy(type = "base", label = "基础 APK", isRequired = true, isDefault = true))
            }
        }
        return result
    }

    private fun parseSplitConfig(configPart: String, path: String): SplitEntry? {
        val abiMatch = ABI_MAP.entries.find { configPart.contains(it.key, ignoreCase = true) }
        if (abiMatch != null) {
            val matched = DEVICE_ABIS.any { it == abiMatch.value }
            return SplitEntry(path, "abi", "${abiMatch.value} 代码", isDefault = matched)
        }
        val densityKey = DENSITY_MAP.keys.find { configPart.contains(it) }
        if (densityKey != null) {
            return SplitEntry(path, "density", "$densityKey(${DENSITY_MAP[densityKey]} DPI) 资源", isDefault = true)
        }
        val localeMatch = Regex("^(?:[a-z]{2})(?:-r?[A-Z]{2})?$").find(configPart)
        if (localeMatch != null) {
            val locale = localeMatch.value
            val lang = when {
                locale.startsWith("zh") -> "中文"
                locale.startsWith("en") -> "English"
                locale.startsWith("ja") -> "日本語"
                locale.startsWith("ko") -> "한국어"
                else -> locale
            }
            return SplitEntry(path, "locale", "$lang 语言", isDefault = true)
        }
        return SplitEntry(path, "unknown", configPart, isDefault = true)
    }
}
