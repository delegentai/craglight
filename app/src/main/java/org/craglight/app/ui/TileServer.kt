package org.craglight.app.ui

import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Read-only MBTiles transport bound exclusively to this device's loopback interface. */
internal class TileServer(files: List<File>, allowedDirectory: File) : Closeable {
    private data class Database(val db: SQLiteDatabase, val minZoom: Int, val maxZoom: Int)
    data class Tiles(val id: String, val url: String, val minZoom: Int, val maxZoom: Int)
    private val closed = AtomicBoolean(false)
    private val root = allowedDirectory.canonicalPath + File.separator
    private val databases = mutableListOf<Database>().apply {
        try {
            for (file in files) {
                require(file.canonicalPath.startsWith(root) && file.extension == "mbtiles") { "Unexpected map pack location" }
                val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
                try {
                    fun metadata(name: String, fallback: Int): Int = db.rawQuery("SELECT value FROM metadata WHERE name = ?", arrayOf(name)).use {
                        if (it.moveToFirst()) it.getString(0).toIntOrNull() ?: fallback else fallback
                    }
                    add(Database(db, metadata("minzoom", 4), metadata("maxzoom", 15)))
                } catch (error: Exception) {
                    db.close()
                    throw error
                }
            }
        } catch (error: Exception) {
            forEach { it.db.close() }
            throw error
        }
    }
    private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1000 }
    private val workers = Executors.newFixedThreadPool(4) { work -> Thread(work, "CragLight-tile-worker").apply { isDaemon = true } }
    val tiles: List<Tiles> = databases.mapIndexed { index, database ->
        Tiles("regional-$index", "http://127.0.0.1:${listener.localPort}/tiles/$index/{z}/{x}/{y}.pbf", database.minZoom, database.maxZoom)
    }
    private val acceptThread = Thread({
        while (!closed.get()) {
            try {
                val socket = listener.accept()
                workers.execute { runCatching { serve(socket) }; runCatching { socket.close() } }
            } catch (_: SocketTimeoutException) {
                // Periodically check closure without keeping the Activity alive.
            } catch (_: Exception) {
                if (!closed.get()) close()
            }
        }
    }, "CragLight-tile-listener").apply { isDaemon = true; start() }

    private fun serve(socket: Socket) {
        socket.soTimeout = 3000
        val input = socket.getInputStream().buffered()
        val header = ByteArrayOutputStream()
        var tail = 0
        while (header.size() < 16_384) {
            val byte = input.read()
            if (byte < 0) return
            header.write(byte)
            tail = (tail shl 8) or byte
            if (tail == 0x0d0a0d0a) break
        }
        if (tail != 0x0d0a0d0a) return
        val request = header.toString("US-ASCII").substringBefore("\r\n")
        val match = Regex("GET /tiles/(\\d+)/(\\d+)/(\\d+)/(\\d+)\\.pbf HTTP/1\\.[01]").matchEntire(request)
        if (match == null) { respond(socket, 404, ByteArray(0)); return }
        val values = match.groupValues.drop(1).map { it.toIntOrNull() }
        if (values.any { it == null }) { respond(socket, 404, ByteArray(0)); return }
        val (index, zoom, x, y) = values.map { it!! }
        val database = databases.getOrNull(index)
        if (database == null || zoom !in 0..22 || x !in 0 until (1 shl zoom) || y !in 0 until (1 shl zoom)) {
            respond(socket, 404, ByteArray(0)); return
        }
        val tmsRow = (1 shl zoom) - 1 - y
        val data = database.db.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?",
            arrayOf(zoom.toString(), x.toString(), tmsRow.toString())
        ).use { if (it.moveToFirst()) it.getBlob(0) else null }
        respond(socket, if (data == null) 204 else 200, data ?: ByteArray(0))
    }

    private fun respond(socket: Socket, status: Int, data: ByteArray) {
        val compressed = data.size >= 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()
        val reason = when (status) { 200 -> "OK"; 204 -> "No Content"; else -> "Not Found" }
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: application/vnd.mapbox-vector-tile\r\n")
            append("Content-Length: ${data.size}\r\n")
            if (compressed) append("Content-Encoding: gzip\r\n")
            append("Cache-Control: public, max-age=31536000, immutable\r\nConnection: close\r\n\r\n")
        }
        socket.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(data); flush() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { listener.close() }
        workers.shutdownNow()
        databases.forEach { runCatching { it.db.close() } }
    }
}
