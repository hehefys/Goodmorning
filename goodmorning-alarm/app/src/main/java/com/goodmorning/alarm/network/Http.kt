package com.goodmorning.alarm.network

import com.goodmorning.alarm.util.Constants
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * OkHttp 单例与抖音请求头常量。
 *
 * - [client]：RSSHub 拉取用，默认策略即可；
 * - [downloadClient]：抖音直链下载用，自动附加移动端 UA + Referer（防盗链，调研结论），
 *   OkHttp 默认 followRedirects(true) 自动跟随 302 到 CDN。
 */
object Http {

    /**
     * RSSHub 拉取客户端：连接 15s / 读 30s / 整次调用 45s 封顶。
     * callTimeout 是总闸：readTimeout 只管单次 read 间隔，代理半开连接滴字节时
     * 整个同步可能无限拖延（实测 12:01 同步发起后无「同步结束」日志直到进程被杀）。
     */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    /** 抖音视频下载客户端：附加移动端 UA + Referer，长读超时以容纳大文件 */
    val downloadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor(DouyinHeaderInterceptor())
            .build()
    }

    /**
     * 为抖音直链请求附加防盗链必需的请求头。
     */
    private class DouyinHeaderInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val request = chain.request().newBuilder()
                .header("User-Agent", Constants.DOUYIN_UA)
                .header("Referer", Constants.DOUYIN_REFERER)
                .build()
            return chain.proceed(request)
        }
    }
}
