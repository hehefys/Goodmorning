package com.goodmorning.alarm.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 本地文件日志（filesDir/logs/，按天轮转，保留 [Constants.LOG_RETENTION_DAYS] 天）。
 *
 * P0-4 验收要求：所有兜底降级与同步失败必须同时写入本文件日志。
 * 写文件在单线程 Executor 中排队执行，调用方可安全地在任意线程调用。
 */
object AppLogger {

    private const val TAG = "GMA/Logger"

    private val logFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())

    /** 单写线程，保证同一天文件追加的串行性 */
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "gma-file-logger").apply { isDaemon = true }
    }

    fun d(tag: String, message: String) = write("D", tag, message, null)
    fun i(tag: String, message: String) = write("I", tag, message, null)
    fun w(tag: String, message: String, tr: Throwable? = null) = write("W", tag, message, tr)
    fun e(tag: String, message: String, tr: Throwable? = null) = write("E", tag, message, tr)

    private fun write(level: String, tag: String, message: String, tr: Throwable?) {
        // Logcat 始终输出
        when (level) {
            "E" -> Log.e(tag, message, tr)
            "W" -> Log.w(tag, message, tr)
            "I" -> Log.i(tag, message)
            else -> Log.d(tag, message)
        }
        // 文件日志异步落盘
        val appContext = appContext ?: return
        val line = buildString {
            append(timestamp()).append(' ').append(level).append('/').append(tag).append(": ")
            append(message)
            if (tr != null) {
                append(" | ").append(tr.javaClass.simpleName)
                tr.message?.let { append(": ").append(it) }
            }
        }
        executor.execute {
            try {
                writeLine(appContext, line)
            } catch (e: Exception) {
                // 日志系统自身故障只打 logcat，绝不上抛
                Log.e(TAG, "写文件日志失败", e)
            }
        }
    }

    private fun timestamp(): String = synchronized(logFormat) { logFormat.format(Date()) }

    private fun writeLine(context: Context, line: String) {
        val logDir = File(context.filesDir, Constants.LOG_DIR)
        if (!logDir.exists()) logDir.mkdirs()
        rotateIfNeeded(logDir)
        val file = File(logDir, "gma-${TimeUtils.localDate()}.log")
        file.appendText(line + System.lineSeparator())
    }

    /** 清理超过保留期的旧日志文件 */
    private fun rotateIfNeeded(logDir: File) {
        val files = logDir.listFiles() ?: return
        val retention = Constants.LOG_RETENTION_DAYS
        files.sortedByDescending { it.name }.drop(retention).forEach { it.delete() }
    }

    // ---- appContext 注入（在 Application.onCreate 中调用一次） ----
    @Volatile
    private var appContext: Context? = null

    /** 由 GoodMorningApp.onCreate 注入 application context */
    fun init(context: Context) {
        appContext = context.applicationContext
    }
}
