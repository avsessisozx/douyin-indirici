package io.github.avsessisozx.douyinindirici

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Parçalı (Range) isteği destekleyen küçük sunucu. ranges=false ise Range'i yok sayıp tüm dosyayı verir. */
private class RangeServer(val data: ByteArray, val ranges: Boolean = true) {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort
    val requests: MutableList<String?> = Collections.synchronizedList(ArrayList())
    @Volatile private var running = true

    fun start(): RangeServer {
        thread(isDaemon = true) {
            while (running) {
                val c = try { socket.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { serve(c) }
            }
        }
        return this
    }

    private fun serve(c: Socket) {
        c.use {
            try {
                val reader = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                reader.readLine() ?: return
                var range: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.lowercase().startsWith("range:")) range = line.substringAfter(':').trim()
                }
                requests.add(range)
                val out = it.getOutputStream()
                if (range != null && ranges) {
                    val (a, b) = range.substringAfter('=').split('-').map { x -> x.toInt() }
                    val body = data.copyOfRange(a, b + 1)
                    out.write(("HTTP/1.1 206 Partial\r\nContent-Type: video/mp4\r\nContent-Range: bytes $a-$b/${data.size}\r\n" +
                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    out.write(body)
                } else {
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    out.write(data)
                }
                out.flush()
            } catch (e: Exception) {
            }
        }
    }

    fun stop() { running = false; try { socket.close() } catch (e: Exception) {} }
}

class ChunkedFetchTest {
    private val data = ByteArray(5 * 1024 * 1024 + 123) { (it * 31 + it / 7).toByte() }
    private lateinit var out: File

    @Before fun setUp() { out = Files.createTempDirectory("di-chunk").toFile() }
    @After fun tearDown() { out.deleteRecursively() }

    private fun fetch(server: RangeServer, chunks: Int): Fetcher.Result =
        Fetcher.fetch(listOf("http://127.0.0.1:${server.port}/v.mp4"), "Kişi/v", ".mp4", ".mp4", emptyMap(),
            FileSink(out), AtomicBoolean(false), null, chunks = chunks)

    @Test
    fun chunkedDownloadIsIdentical() {
        val server = RangeServer(data).start()
        try {
            val r = fetch(server, 4)
            assertTrue(r.error?.key, r.ok)
            assertArrayEquals(data, File(out, "Kişi/v.mp4").readBytes())
            assertTrue("en az 4 parça isteği olmalı", server.requests.count { it != null && it != "bytes=0-0" } >= 4)
            assertFalse(out.walkTopDown().any { it.name.endsWith(".part") })
        } finally { server.stop() }
    }

    @Test
    fun serverWithoutRangesFallsBackToSingleStream() {
        val server = RangeServer(data, ranges = false).start()
        try {
            val r = fetch(server, 4)
            assertTrue(r.error?.key, r.ok)
            assertArrayEquals(data, File(out, "Kişi/v.mp4").readBytes())
        } finally { server.stop() }
    }

    @Test
    fun singleConnectionMode() {
        val server = RangeServer(data).start()
        try {
            val r = fetch(server, 1)
            assertTrue(r.ok)
            assertEquals(1, server.requests.size)
            assertTrue(server.requests.all { it == null })
        } finally { server.stop() }
    }
}
