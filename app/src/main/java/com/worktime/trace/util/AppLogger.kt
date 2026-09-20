package com.worktime.trace.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用内日志：同时写 logcat 与本机文件（files/logs/app-yyyyMMdd.log），
 * 供设置页"导出日志"生成排障文件发给开发者。
 *
 * 单日文件超过 1MB 时轮转为 .old（仅保留一代）；只保留最近 7 天。
 */
object AppLogger {
    private const val TAG = "WorkTimeTrace"
    private const val DIR_NAME = "logs"
    private const val MAX_BYTES = 1024 * 1024L
    private const val KEEP_DAYS = 7L

    private var dir: File? = null
    private val lineFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)

    fun init(context: Context) {
        dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        cleanup()
        d("AppLogger 初始化完成")
    }

    fun d(msg: String) = write("D", msg, null)

    fun w(msg: String, tr: Throwable? = null) = write("W", msg, tr)

    @Synchronized
    private fun write(level: String, msg: String, tr: Throwable?) {
        if (level == "W") Log.w(TAG, msg, tr) else Log.d(TAG, msg)
        val d = dir ?: return
        runCatching {
            val f = File(d, "app-${dayFmt.format(Date())}.log")
            if (f.length() > MAX_BYTES) {
                val old = File(d, f.name + ".old")
                old.delete()
                f.renameTo(old)
            }
            val line = buildString {
                append(lineFmt.format(Date())).append(' ').append(level).append(' ').append(msg)
                if (tr != null) {
                    append(" | ").append(tr.javaClass.simpleName)
                    tr.message?.let { append(": ").append(it) }
                }
                append('\n')
            }
            f.appendText(line)
        }
    }

    /** 合并最近日志为单个导出文件；无日志返回 null。 */
    @Synchronized
    fun export(): File? {
        val d = dir ?: return null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(d, "niuma-log-$stamp.txt")
        runCatching {
            out.delete()
            val files = d.listFiles { f -> f.name.startsWith("app-") }
                ?.sortedBy { it.name } ?: return null
            if (files.isEmpty()) return null
            files.forEach { src ->
                out.appendText("===== ${src.name} =====\n")
                out.appendText(src.readText())
                out.appendText("\n")
            }
        }
        return out.takeIf { it.exists() && it.length() > 0L }
    }

    private fun cleanup() {
        val d = dir ?: return
        runCatching {
            val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24 * 3600_000L
            d.listFiles()?.forEach { f ->
                if (f.lastModified() < cutoff) f.delete()
            }
        }
    }
}
