package com.goodmorning.alarm.network

import com.goodmorning.alarm.util.AppLogger
import com.goodmorning.alarm.util.Constants
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * 视频下载器：同步阻塞实现（调用方负责在 Dispatchers.IO 执行）。
 *
 * 流程：OkHttp（移动端 UA + Referer + 自动跟随 302）→ 写 {dest}.part →
 * 校验 Content-Length 与 mp4 魔数 → 原子 rename 为目标文件。
 */
class VideoDownloader {

    /**
     * 下载 [url] 到 [dest]（dest 形如 filesDir/videos/{aweme_id}.mp4）。
     * @return 下载成功的本地文件（即 [dest]）
     * @throws IOException 网络/校验失败（由 SyncEngine 归入 DOWNLOAD: 错误码）
     */
    fun download(url: String, dest: File): File {
        if (!url.startsWith("http")) throw IOException("非法下载地址: $url")
        val partFile = File(dest.parentFile, dest.name + ".part")
        dest.parentFile?.let { if (!it.exists()) it.mkdirs() }

        val request = Request.Builder().url(url).get().build()
        Http.downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}：${response.message}")
            }
            val body = response.body ?: throw IOException("响应体为空")
            val expectedLength = body.contentLength()

            // 写 .part 中间文件
            body.byteStream().use { input ->
                partFile.outputStream().use { output ->
                    input.copyTo(output, BUFFER_SIZE)
                }
            }

            // 校验一：大小（服务器给定了 Content-Length 时必须一致）
            val actualLength = partFile.length()
            if (expectedLength > 0 && actualLength != expectedLength) {
                partFile.delete()
                throw IOException("大小校验失败：期望 $expectedLength 实际 $actualLength")
            }
            // 校验二：mp4 魔数（第 4-8 字节应为 "ftyp"）
            if (!isMp4File(partFile)) {
                partFile.delete()
                throw IOException("文件魔数校验失败（非 mp4，可能是防盗链错误页）")
            }

            // 原子 rename 为最终文件
            if (dest.exists()) dest.delete()
            if (!partFile.renameTo(dest)) {
                partFile.delete()
                throw IOException("rename 失败：${partFile.path}")
            }
            AppLogger.i(TAG, "下载完成 ${dest.name}（${actualLength / 1024}KB）")
            return dest
        }
    }

    /** 检查文件头是否为 mp4 容器（ftyp box） */
    private fun isMp4File(file: File): Boolean {
        if (file.length() < MIN_VALID_FILE_BYTES) return false
        return file.inputStream().use { input ->
            val header = ByteArray(12)
            val read = input.read(header)
            read >= 12 &&
                header[4] == 'f'.code.toByte() &&
                header[5] == 't'.code.toByte() &&
                header[6] == 'y'.code.toByte() &&
                header[7] == 'p'.code.toByte()
        }
    }

    private companion object {
        const val TAG = Constants.TAG_PREFIX + "Download"
        const val BUFFER_SIZE = 64 * 1024
        const val MIN_VALID_FILE_BYTES = 64 * 1024L
    }
}
