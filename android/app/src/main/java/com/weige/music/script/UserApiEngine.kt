package com.weige.music.script

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 洛雪音源脚本的执行引擎。
 *
 * 音源脚本是 JS，必须用真正的 JS 引擎跑。这里沿用洛雪移动版的方案：
 * QuickJS + 一份预加载脚本（assets/script/user-api-preload.js）把 `globalThis.lx` 搭好。
 *
 * 线程模型（QuickJS 不是线程安全的，所有 JS 操作都在同一个线程上）：
 *
 *   UI/IO 线程              JS 线程（HandlerThread）            OkHttp 线程
 *   ─────────              ──────────────────────            ────────────
 *   getUrl() ──post──▶  __lx_native__('request') ──┐
 *                       脚本要去发 HTTP             │
 *                       调 __lx_native_call__ ◀─────┘
 *                            │
 *                            └─post──▶ 发起请求 ──────────▶ 拿到响应
 *                                                        │
 *                        __lx_native__('response') ◀─post─┘
 *                       脚本回调拿到 body，解析出直链
 *                       调 __lx_native_call__('response')
 *                            │
 *   deferred.complete ◀──────┘
 */
class UserApiEngine(private val appContext: Context) {

    companion object {
        private const val TAG = "UserApi"
        private const val TIMEOUT_MS = 8_000L
    }

    private val thread = HandlerThread("weige-js").apply { start() }
    private val jsHandler = Handler(thread.looper)

    private var js: QuickJSContext? = null
    private var key: String = UUID.randomUUID().toString()

    /** 取流请求：requestKey → 结果 */
    private val apiPending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    private var initDeferred: CompletableDeferred<JSONObject?>? = null
    private var sources: JSONObject? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /* ------------------------------------------------------------ 对外接口 */

    /**
     * 加载脚本。返回脚本上报的 sources 声明；失败返回 null。
     *
     * 注意：**不能在 JS 线程里阻塞等 init**。有些脚本用 setTimeout 延迟上报，
     * 而 setTimeout 的回调也是投递到 JS 线程的 —— 一旦阻塞，回调永远进不来，直接死锁。
     * 所以这里只在外部挂个 deferred，等 onNativeCall("init") 把它填上。
     */
    suspend fun load(script: String, meta: ScriptMeta): JSONObject? {
        val init = CompletableDeferred<JSONObject?>()
        initDeferred = init

        jsHandler.post {
            try {
                buildEnv(script, meta)
            } catch (e: Throwable) {
                Log.e(TAG, "load failed: ${e.message}")
                if (!init.isCompleted) init.complete(null)
            }
        }

        val info = withTimeoutOrNull(15_000) { init.await() }
        sources = info?.optJSONObject("sources")
        return sources
    }

    /**
     * 取直链。source 是平台（kw/tx/wy/kg/mg），quality 是脚本认识的档位（128k/320k/flac）。
     */
    suspend fun getUrl(source: String, musicInfo: JSONObject, quality: String): String? {
        val requestKey = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JSONObject>()
        apiPending[requestKey] = deferred

        val payload = JSONObject().apply {
            put("requestKey", requestKey)
            put(
                "data",
                JSONObject().apply {
                    put("source", source)
                    put("action", "musicUrl")
                    put(
                        "info",
                        JSONObject().apply {
                            put("type", quality)
                            put("musicInfo", musicInfo)
                        }
                    )
                }
            )
        }

        jsHandler.post { callJs("request", payload.toString()) }

        val result = withTimeoutOrNull(TIMEOUT_MS) { deferred.await() }
        apiPending.remove(requestKey)
        if (result == null) {
            Log.w(TAG, "getUrl timeout ($source/$quality)")
            return null
        }

        if (!result.optBoolean("status")) {
            Log.w(TAG, "getUrl failed: ${result.optString("errorMessage")}")
            return null
        }
        return result.optJSONObject("result")
            ?.optJSONObject("data")
            ?.optString("url")
            ?.takeIf { it.startsWith("http") }
    }

    fun destroy() {
        jsHandler.post {
            try {
                js?.destroy()
            } catch (_: Throwable) {
            }
            js = null
        }
        thread.quitSafely()
    }

    /* -------------------------------------------------------------- 环境构建 */

    private fun buildEnv(script: String, meta: ScriptMeta) {
        QuickJSLoader.init()
        key = UUID.randomUUID().toString()

        js?.destroy()
        val ctx = QuickJSContext.create()
        js = ctx

        // 必须设 Console：音源脚本里到处是 console.log，
        // 不设的话 QuickJS 会直接抛「you should be set a stdout of platform to console.stdout」，
        // 脚本 evaluate 一半就崩，整个引擎等于没起来。
        ctx.setConsole(LogcatConsole())

        registerNativeFunctions(ctx)

        val preload = appContext.assets.open("script/user-api-preload.js")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        ctx.evaluate(preload)

        // preload 里定义的入口：把脚本环境搭起来
        ctx.globalObject.getJSFunction("lx_setup").call(
            key, meta.id, meta.name, meta.description, meta.version, meta.author, meta.homepage, script
        )

        // 执行脚本自身：它会 send(inited, {sources}) 回来
        try {
            ctx.evaluate(script)
        } catch (e: Throwable) {
            Log.e(TAG, "script evaluate failed: ${e.message}")
            callJs("__run_error__", null)
        }
    }

    private fun registerNativeFunctions(ctx: QuickJSContext) {
        val g = ctx.globalObject

        g.setProperty("__lx_native_call__") { args ->
            val callKey = args.getOrNull(0) as? String
            if (callKey == key) {
                onNativeCall(args.getOrNull(1) as? String ?: "", args.getOrNull(2) as? String ?: "")
            }
            null
        }

        g.setProperty("__lx_native_call__set_timeout") { args ->
            val id = (args.getOrNull(0) as? Number)?.toInt() ?: return@setProperty null
            val ms = (args.getOrNull(1) as? Number)?.toLong() ?: 0L
            jsHandler.postDelayed({ callJs("__set_timeout__", id.toString()) }, ms.coerceAtMost(60_000))
            null
        }

        g.setProperty("__lx_native_call__utils_str2b64") { args ->
            val s = args.getOrNull(0) as? String ?: ""
            android.util.Base64.encodeToString(s.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        }

        g.setProperty("__lx_native_call__utils_b642buf") { args ->
            val s = args.getOrNull(0) as? String ?: ""
            val bytes = android.util.Base64.decode(s, android.util.Base64.NO_WRAP)
            bytes.joinToString(prefix = "[", postfix = "]") { it.toString() }
        }

        g.setProperty("__lx_native_call__utils_str2md5") { args ->
            val s = args.getOrNull(0) as? String ?: ""
            val md = java.security.MessageDigest.getInstance("MD5")
            md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }

        // 少数脚本会用到加密接口，先给空实现：拿不到就让它自己抛错，不影响主流程
        g.setProperty("__lx_native_call__utils_aes_encrypt") { "" }
        g.setProperty("__lx_native_call__utils_rsa_encrypt") { "" }
    }

    /* ---------------------------------------------------------- JS → 原生 */

    private fun onNativeCall(action: String, dataStr: String) {
        val data = runCatching { JSONObject(dataStr) }.getOrNull() ?: JSONObject()

        when (action) {
            "init" -> {
                if (data.optBoolean("status")) initDeferred?.complete(data.optJSONObject("info"))
                else initDeferred?.complete(null)
            }

            "request" -> {
                // 脚本要自己去发 HTTP
                val requestKey = data.optString("requestKey")
                val url = data.optString("url")
                val options = data.optJSONObject("options") ?: JSONObject()
                if (url.isNotEmpty()) sendHttp(requestKey, url, options)
            }

            "cancelRequest" -> {
                // 请求已经发出去了，交给 OkHttp 自己超时，这里只记一笔
                Log.d(TAG, "cancelRequest: ${data.optString("requestKey")}")
            }

            "response" -> {
                // 脚本算出了直链
                val requestKey = data.optString("requestKey")
                apiPending[requestKey]?.complete(data)
            }

            "showUpdateAlert" -> Log.i(TAG, "脚本提示有新版本：${data.optString("log")}")

            else -> Log.d(TAG, "unhandled action: $action")
        }
    }

    /* ---------------------------------------------------------- 原生 → JS */

    private fun callJs(action: String, data: String?) {
        try {
            val fn = js?.globalObject?.getJSFunction("__lx_native__") ?: return
            if (data == null) fn.call(key, action) else fn.call(key, action, data)
        } catch (e: Throwable) {
            Log.e(TAG, "callJs($action) failed: ${e.message}")
        }
    }

    /* ------------------------------------------------------------- HTTP */

    private fun sendHttp(requestKey: String, url: String, options: JSONObject) {
        val method = options.optString("method", "get").uppercase()
        val headers = options.optJSONObject("headers")

        val builder = Request.Builder().url(url)
        headers?.keys()?.forEach { k ->
            runCatching { builder.header(k, headers.optString(k)) }
        }

        val bodyText = options.optString("body").ifEmpty { null }
        val formText = options.optString("form").ifEmpty { null }
        val mediaType = "application/x-www-form-urlencoded".toMediaTypeOrNull()
        val body = when {
            bodyText != null -> bodyText.toRequestBody(null)
            formText != null -> formText.toRequestBody(mediaType)
            method != "GET" && method != "HEAD" -> "".toRequestBody(null)
            else -> null
        }

        val request = runCatching { builder.method(method, body).build() }.getOrNull()
        if (request == null) {
            replyHttp(requestKey, null, "bad request")
            return
        }

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                replyHttp(requestKey, null, e.message ?: "network error")
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use { r ->
                    val text = runCatching { r.body?.string().orEmpty() }.getOrDefault("")
                    val payload = JSONObject().apply {
                        put("statusCode", r.code)
                        put("statusMessage", r.message)
                        put(
                            "headers",
                            JSONObject().apply {
                                r.headers.names().forEach { put(it, r.headers[it]) }
                            }
                        )
                        put("body", text)
                    }
                    replyHttp(requestKey, payload, null)
                }
            }
        })
    }

    private fun replyHttp(requestKey: String, response: JSONObject?, error: String?) {
        val payload = JSONObject().apply {
            put("requestKey", requestKey)
            if (error != null) {
                put("error", error)
                put("response", JSONObject.NULL)
            } else {
                put("error", JSONObject.NULL)
                put("response", response)
            }
        }
        jsHandler.post { callJs("response", payload.toString()) }
    }
}

/** 脚本元信息，来自脚本头部的注释块 */
data class ScriptMeta(
    val id: String,
    val name: String,
    val description: String = "",
    val version: String = "",
    val author: String = "",
    val homepage: String = "",
)

/**
 * 把脚本里的 console 输出接到 Logcat。
 * 混淆脚本会打很多调试日志，这里统一截断，避免刷爆缓冲区。
 */
private class LogcatConsole : QuickJSContext.Console {
    private fun cut(info: String?): String =
        (info ?: "").let { if (it.length > 400) it.take(400) + "…" else it }

    override fun log(info: String?) = Unit
    override fun info(info: String?) = Unit
    override fun warn(info: String?) {
        Log.w("UserApi", "[脚本] ${cut(info)}")
    }

    override fun error(info: String?) {
        Log.e("UserApi", "[脚本] ${cut(info)}")
    }
}
