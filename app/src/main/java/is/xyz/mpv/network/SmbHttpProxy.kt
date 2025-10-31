package `is`.xyz.mpv.network

import android.util.Log
import jcifs.CIFSContext
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.DEFAULT_BUFFER_SIZE

/**
 * Very small HTTP proxy that exposes jcifs-backed SMB files as loopback URLs.
 * mpv opens the generated http://127.0.0.1:<port>/stream/<token> endpoints
 * and this proxy forwards requests to the underlying remote SMB share.
 */
object SmbHttpProxy {
    private const val TAG = "SmbHttpProxy"
    private const val PATH_PREFIX = "/stream/"

    private val serverRunning = AtomicBoolean(false)
    private val mapping = ConcurrentHashMap<String, Descriptor>()
    private val workerPool = Executors.newCachedThreadPool()

    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var listenPort: Int = -1

    private data class Descriptor(
        val context: CIFSContext,
        val smbPath: String,
        val displayName: String?
    )

    /**
     * Register an SMB path to be served over the local loopback HTTP proxy.
     * Returns a URL suitable for consumption by mpv/FFmpeg.
     */
    fun register(context: CIFSContext, smbPath: String, displayName: String?): String {
        ensureServer()
        val token = UUID.randomUUID().toString()
        mapping[token] = Descriptor(context, smbPath, displayName)
        val port = listenPort
        Log.d(TAG, "Registered SMB path for proxy: $smbPath -> token=$token")
        return "http://127.0.0.1:$port$PATH_PREFIX$token"
    }

    fun resolveDisplayName(url: String): String? {
        val token = url.substringAfter(PATH_PREFIX, "")
        if (token.isEmpty()) return null
        return mapping[token]?.displayName
    }

    private fun ensureServer() {
        if (serverRunning.get()) {
            return
        }
        synchronized(this) {
            if (serverRunning.get()) {
                return
            }
            try {
                val loopback = InetAddress.getByName("127.0.0.1")
                val socket = ServerSocket(0, 0, loopback)
                listenPort = socket.localPort
                serverSocket = socket
                serverRunning.set(true)
                Thread({ acceptLoop(socket) }, "SmbHttpProxy").apply {
                    isDaemon = true
                    start()
                }
                Log.i(TAG, "Started SMB HTTP proxy on port $listenPort")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to start SMB HTTP proxy", e)
                throw IllegalStateException("Cannot start SMB HTTP proxy", e)
            }
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        try {
            while (true) {
                val client = socket.accept()
                workerPool.execute { handleClient(client) }
            }
        } catch (e: IOException) {
            Log.e(TAG, "HTTP proxy accept loop terminated", e)
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))
                val requestLine = reader.readLine() ?: return
                val requestParts = requestLine.split(" ")
                if (requestParts.size < 2) {
                    sendSimpleResponse(client, 400, "Bad Request")
                    return
                }
                val method = requestParts[0]
                val path = requestParts[1]

                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        val name = line.substring(0, idx).trim().lowercase(Locale.US)
                        val value = line.substring(idx + 1).trim()
                        headers[name] = value
                    }
                }

                if (method != "GET" && method != "HEAD") {
                    sendSimpleResponse(client, 405, "Method Not Allowed")
                    return
                }

                if (!path.startsWith(PATH_PREFIX)) {
                    sendSimpleResponse(client, 404, "Not Found")
                    return
                }

                val token = path.removePrefix(PATH_PREFIX)
                val descriptor = mapping[token]
                if (descriptor == null) {
                    sendSimpleResponse(client, 404, "Not Found")
                    return
                }

                serveSmbFile(client, descriptor, headers, method == "HEAD")
            } catch (e: Exception) {
                Log.e(TAG, "Error while handling proxy request", e)
            }
        }
    }

    private fun serveSmbFile(
        socket: Socket,
        descriptor: Descriptor,
        headers: Map<String, String>,
        headOnly: Boolean
    ) {
        val smbFile = SmbFile(descriptor.smbPath, descriptor.context)
        if (!smbFile.exists()) {
            sendSimpleResponse(socket, 404, "Not Found")
            return
        }

        val length = runCatching { smbFile.length() }.getOrElse {
            Log.e(TAG, "Failed to get SMB file length for ${descriptor.smbPath}", it)
            sendSimpleResponse(socket, 500, "Internal Server Error")
            return
        }

        var start = 0L
        var end = length - 1
        var partial = false

        headers["range"]?.let { rangeValue ->
            val range = parseRange(rangeValue, length)
            if (range == null) {
                sendRangeNotSatisfiable(socket, length)
                return
            } else {
                start = range.first
                end = range.second
                partial = true
            }
        }

        val contentLength = (end - start + 1).coerceAtLeast(0L)
        val statusLine = if (partial) "HTTP/1.1 206 Partial Content" else "HTTP/1.1 200 OK"
        val output = BufferedOutputStream(socket.getOutputStream())
        val builder = StringBuilder().apply {
            append(statusLine).append("\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Type: application/octet-stream\r\n")
            append("Content-Length: ").append(contentLength).append("\r\n")
            if (partial) {
                append("Content-Range: bytes ").append(start).append("-").append(end).append("/").append(length).append("\r\n")
            }
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(builder.toString().toByteArray(StandardCharsets.US_ASCII))
        output.flush()

        if (headOnly || contentLength <= 0) {
            output.flush()
            return
        }

        var remaining = contentLength
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        try {
            SmbRandomAccessFile(smbFile, "r").use { raf ->
                raf.seek(start)
                while (remaining > 0) {
                    val toRead = if (remaining > buffer.size) buffer.size else remaining.toInt()
                    val read = raf.read(buffer, 0, toRead)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    remaining -= read.toLong()
                }
                output.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error streaming SMB file ${descriptor.smbPath}", e)
        }
    }

    private fun parseRange(range: String, length: Long): Pair<Long, Long>? {
        if (!range.startsWith("bytes=")) return null
        val value = range.removePrefix("bytes=").trim()
        val parts = value.split("-", limit = 2)
        if (parts.isEmpty()) return null

        val start = parts[0].takeIf { it.isNotEmpty() }?.toLongOrNull()
        val end = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toLongOrNull()

        if (start == null && end == null) return null

        val resolvedStart: Long
        val resolvedEnd: Long
        if (start != null) {
            resolvedStart = start.coerceAtLeast(0)
            resolvedEnd = (end ?: (length - 1)).coerceAtMost(length - 1)
            if (resolvedStart > resolvedEnd) return null
        } else {
            // suffix range: bytes=-N
            val suffix = end ?: return null
            if (suffix <= 0) return null
            resolvedEnd = length - 1
            resolvedStart = (length - suffix).coerceAtLeast(0)
        }
        return resolvedStart to resolvedEnd
    }

    private fun sendSimpleResponse(socket: Socket, status: Int, message: String) {
        runCatching {
            val output = socket.getOutputStream()
            val body = "$status $message"
            val response = buildString {
                append("HTTP/1.1 ").append(status).append(' ').append(message).append("\r\n")
                append("Content-Length: ").append(body.toByteArray(StandardCharsets.US_ASCII).size).append("\r\n")
                append("Connection: close\r\n")
                append("Content-Type: text/plain\r\n")
                append("\r\n")
                append(body)
            }
            output.write(response.toByteArray(StandardCharsets.US_ASCII))
            output.flush()
        }.onFailure {
            Log.e(TAG, "Failed to send simple response $status", it)
        }
    }

    private fun sendRangeNotSatisfiable(socket: Socket, length: Long) {
        runCatching {
            val output = socket.getOutputStream()
            val response = buildString {
                append("HTTP/1.1 416 Range Not Satisfiable\r\n")
                append("Content-Length: 0\r\n")
                append("Connection: close\r\n")
                append("Content-Range: bytes */").append(length).append("\r\n")
                append("\r\n")
            }
            output.write(response.toByteArray(StandardCharsets.US_ASCII))
            output.flush()
        }.onFailure {
            Log.e(TAG, "Failed to send 416 response", it)
        }
    }
}
