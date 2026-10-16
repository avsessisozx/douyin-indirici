package io.github.avsessisozx.douyinindirici

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** Testler için en küçük HTTP sunucusu (Android test ortamında JDK'nın sunucusu yok). */
class TinyHttpServer(private val handler: (path: String, headers: Map<String, String>) -> Triple<Int, String, ByteArray>) {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort
    @Volatile private var running = true

    fun start(): TinyHttpServer {
        thread(isDaemon = true) {
            while (running) {
                val client = try { socket.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { serve(client) }
            }
        }
        return this
    }

    private fun serve(client: Socket) {
        client.use { c ->
            try {
                val reader = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.ISO_8859_1))
                val requestLine = reader.readLine() ?: return
                val path = requestLine.split(" ").getOrNull(1)?.substringBefore('?') ?: "/"
                val headers = HashMap<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                val (code, type, body) = handler(path, headers)
                val out = c.getOutputStream()
                out.write(("HTTP/1.1 $code X\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                    .toByteArray(Charsets.ISO_8859_1))
                out.write(body)
                out.flush()
            } catch (e: Exception) {
            }
        }
    }

    fun stop() {
        running = false
        try { socket.close() } catch (e: Exception) {}
    }
}
