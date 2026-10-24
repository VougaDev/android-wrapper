package com.vougadev.shell.auth

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException

/** A sealer the JVM can run: what it writes is not the token, and only what it wrote opens. */
class FakeSealer : Sealer {
    override fun seal(plain: String): String = "sealed:" + plain.reversed()

    override fun open(sealed: String): String? = if (sealed.startsWith("sealed:")) sealed.removePrefix("sealed:").reversed() else null
}

class MapValues(val map: MutableMap<String, String> = mutableMapOf()) : Values {
    override fun get(key: String): String? = map[key]

    override fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

/** The WebView's jar as a string the tests read and the keeper writes (`Set-Cookie` lines turned into `a=1; b=2`). */
class FakeJar(var text: String? = null) : CookieJarPort {
    val puts = mutableListOf<List<String>>()

    override fun jar(): String? = text

    override fun put(setCookies: List<String>) {
        puts += setCookies
        val kept = text.orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }.associateBy { it.substringBefore('=') }.toMutableMap()
        setCookies.map { it.substringBefore(';').trim() }.forEach { kept[it.substringBefore('=')] = it }
        text = kept.values.joinToString("; ")
    }

    override fun clear() {
        text = null
    }
}

/** An OkHttp client that answers from [answer] without a network, and keeps every request it was asked. */
class FakeServer(private val answer: (Request) -> Response?) {
    val requests = mutableListOf<Request>()

    val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .addInterceptor(
            Interceptor { chain ->
                val request = chain.request()
                requests += request
                answer(request) ?: throw IOException("no network")
            },
        )
        .build()

    companion object {
        fun response(request: Request, code: Int, body: String = "", headers: List<Pair<String, String>> = emptyList()): Response =
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("")
                .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
                .body(body.toResponseBody())
                .build()
    }
}
