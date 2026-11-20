package com.nuvio.tv.core.homeassistant

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * A small page on the home network to type the Home Assistant connection on a phone (same idea as
 * the live TV PhoneInputServer): only while the dialog that asked for it is open, nothing leaves
 * the home network, the address has a random code in it, and the form is sent by POST so the
 * password doesn't end up in the phone's browser history.
 */
object HaPhoneSetupServer {

    data class Values(val host: String, val port: Int?, val username: String, val password: String?, val deviceName: String)

    /** Page wording, from the app's strings so it follows the device language. */
    data class Texts(
        val title: String,
        val intro: String,
        val host: String,
        val port: String,
        val username: String,
        val password: String,
        val passwordHint: String,
        val deviceName: String,
        val send: String,
        val sent: String,
        val missingHost: String
    )

    class Handle(val url: String, private val socket: ServerSocket) {
        fun stop() { runCatching { socket.close() } }
    }

    /** Null without a home network. [onValues] runs on a background thread. */
    fun start(texts: Texts, current: Values, onValues: (Values) -> Unit): Handle? {
        val ip = localAddress() ?: return null
        val server = bind() ?: return null
        val token = randomToken()
        Thread {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: Exception) { break }
                Thread { serve(client, token, texts, current, onValues) }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; name = "ha-phone-setup"; start() }
        return Handle("http://$ip:${server.localPort}/$token", server)
    }

    private fun bind(): ServerSocket? {
        for (port in 8091..8099) runCatching { ServerSocket(port) }.getOrNull()?.let { return it }
        return runCatching { ServerSocket(0) }.getOrNull()
    }

    private fun randomToken(): String {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val r = java.security.SecureRandom()
        return (1..8).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    private fun localAddress(): String? = runCatching {
        val found = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif -> nif.inetAddresses.toList().filter { it is Inet4Address && it.isSiteLocalAddress }.map { nif.name to it.hostAddress } }
        (found.firstOrNull { it.first.startsWith("wlan") || it.first.startsWith("eth") } ?: found.firstOrNull())?.second
    }.getOrNull()

    private fun serve(client: Socket, token: String, texts: Texts, current: Values, onValues: (Values) -> Unit) {
        runCatching {
            client.use { c ->
                c.soTimeout = 5_000
                val reader = c.getInputStream().bufferedReader(Charsets.UTF_8)
                val request = reader.readLine().orEmpty()
                var length = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        length = line.substringAfter(':').trim().toIntOrNull()?.coerceIn(0, 8_192) ?: 0
                    }
                }
                val method = request.substringBefore(' ')
                val path = request.split(' ').getOrNull(1).orEmpty().substringBefore('?').trim('/')
                val html: String? = when {
                    path != token -> null
                    method == "POST" -> {
                        val body = CharArray(length).let { buf ->
                            var read = 0
                            while (read < length) {
                                val n = reader.read(buf, read, length - read)
                                if (n < 0) break
                                read += n
                            }
                            String(buf, 0, read)
                        }
                        val form = parseForm(body)
                        val host = form["host"].orEmpty().trim()
                        if (host.isEmpty()) {
                            page(texts, current, texts.missingHost, error = true)
                        } else {
                            val values = Values(
                                host = host,
                                port = form["port"]?.trim()?.toIntOrNull(),
                                username = form["username"].orEmpty().trim(),
                                password = form["password"]?.takeIf { it.isNotEmpty() },
                                deviceName = form["name"].orEmpty().trim()
                            )
                            onValues(values)
                            page(texts, values.copy(password = null), texts.sent, error = false)
                        }
                    }
                    else -> page(texts, current, null, error = false)
                }
                val out = c.getOutputStream()
                if (html == null) {
                    val body = "Not found".toByteArray()
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(body)
                } else {
                    val body = html.toByteArray(Charsets.UTF_8)
                    out.write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()
                    )
                    out.write(body)
                }
                out.flush()
            }
        }
    }

    private fun parseForm(body: String): Map<String, String> = body.split('&').mapNotNull { pair ->
        val k = pair.substringBefore('=', "")
        if (k.isEmpty()) return@mapNotNull null
        val v = pair.substringAfter('=', "")
        runCatching { URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8") }.getOrNull()
    }.toMap()

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(t: Texts, v: Values, msg: String?, error: Boolean): String = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(t.title)}</title>
<style>
body{font-family:-apple-system,Roboto,sans-serif;background:#111;color:#eee;margin:0;padding:18px}
h1{font-size:21px;margin:0 0 8px}
p{color:#aaa;font-size:15px;margin:6px 0 14px}
label{display:block;font-size:14px;color:#bbb;margin:14px 0 6px}
small{color:#888}
input{width:100%;box-sizing:border-box;font-size:17px;padding:14px;border-radius:10px;border:1px solid #333;background:#1b1b1f;color:#eee}
button{margin-top:18px;font-size:18px;padding:14px 22px;border:0;border-radius:10px;background:#fff;color:#111;font-weight:600;width:100%}
#msg{font-size:16px;margin-top:14px;color:${if (error) "#f77" else "#7f7"}}
</style></head><body>
<h1>${esc(t.title)}</h1>
<p>${esc(t.intro)}</p>
<form method="post">
<label>${esc(t.host)}</label>
<input name="host" value="${esc(v.host)}" placeholder="192.168.1.10" autocomplete="off" autocapitalize="off" autocorrect="off" inputmode="url">
<label>${esc(t.port)}</label>
<input name="port" value="${v.port ?: HomeAssistantSettings.DEFAULT_PORT}" inputmode="numeric">
<label>${esc(t.username)}</label>
<input name="username" value="${esc(v.username)}" autocomplete="off" autocapitalize="off" autocorrect="off">
<label>${esc(t.password)} <small>${esc(t.passwordHint)}</small></label>
<input name="password" type="password" autocomplete="new-password">
<label>${esc(t.deviceName)}</label>
<input name="name" value="${esc(v.deviceName)}">
<button type="submit">${esc(t.send)}</button>
</form>
<div id="msg">${esc(msg ?: "")}</div>
</body></html>"""
}
