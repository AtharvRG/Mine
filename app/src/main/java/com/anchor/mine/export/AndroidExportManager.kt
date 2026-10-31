// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.export

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.anchor.core.export.ExportManager
import com.anchor.core.export.ExportStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.*

class AndroidExportManager(
    private val context: android.content.Context,
    private val dbHelper: SupportSQLiteOpenHelper,
    private val dbKey: ByteArray
) : ExportManager {

    override suspend fun exportTo(file: File, passphrase: String, includeApiKeys: Boolean, includeEmbeddings: Boolean): ExportStats = withContext(Dispatchers.IO) {
        if (passphrase.length < 8) throw IllegalArgumentException("Passphrase too weak (minimum 8 characters)")
        
        val rand = SecureRandom()
        val salt = ByteArray(16).apply { rand.nextBytes(this) }
        val wrapNonce = ByteArray(12).apply { rand.nextBytes(this) }
        val noncePrefix = ByteArray(8).apply { rand.nextBytes(this) }
        val dek = ByteArray(32).apply { rand.nextBytes(this) }
        
        val kek = generateKek(passphrase, salt)
        val wrappedDek = wrapKey(dek, kek, wrapNonce)
        
        val headerBuf = ByteBuffer.allocate(106)
        headerBuf.put("MNEX".toByteArray())
        headerBuf.putShort(1) 
        val flags = 1 // bit0 = gzip. includeEmbeddings ignored in v1 as vector DB isn't serialized yet.
        headerBuf.putShort(flags.toShort())
        headerBuf.put(1) // kdf_id = 1
        headerBuf.putInt(65536) // memory
        headerBuf.putInt(3) // iterations
        headerBuf.put(1) // parallelism
        headerBuf.put(salt)
        headerBuf.put(wrapNonce)
        headerBuf.put(wrappedDek)
        headerBuf.putInt(1024 * 1024) 
        headerBuf.put(noncePrefix)
        
        val headerBytes = headerBuf.array()
        val headerHash = MessageDigest.getInstance("SHA-256").digest(headerBytes)
        
        var messages = 0
        var nodes = 0
        var events = 0
        
        val tmpFile = File(file.parentFile, file.name + ".tmp")
        
        try {
            FileOutputStream(tmpFile).use { fos ->
                fos.write(headerBytes)
                
                ChunkedAesOutputStream(fos, dek, noncePrefix, headerHash, 1024 * 1024).use { chunkedOut ->
                    GZIPOutputStream(chunkedOut).use { gzipOut ->
                        val digestStream = HashingOutputStream(gzipOut)
                        OutputStreamWriter(digestStream, Charsets.UTF_8).use { writer ->
                            val db = dbHelper.readableDatabase
                            
                            val manifest = buildJsonObject {
                                put("t", "manifest")
                                put("v", 1)
                                put("d", buildJsonObject { put("app_version", "1.0.0") })
                            }
                            writer.write(manifest.toString() + "\n")
                            
                            messages = exportTable(db, "raw_message", writer, includeApiKeys)
                            events = exportTable(db, "raw_event", writer, includeApiKeys)
                            nodes = exportTable(db, "node", writer, includeApiKeys)
                            exportTable(db, "edge", writer, includeApiKeys)
                            exportTable(db, "soul_fact", writer, includeApiKeys)
                            exportTable(db, "app_meta", writer, includeApiKeys)
                            exportTable(db, "day_rule", writer, includeApiKeys)
                            exportTable(db, "reminder", writer, includeApiKeys)
                            exportTable(db, "proposal", writer, includeApiKeys)
                            
                            writer.flush()
                            val footer = buildJsonObject {
                                put("t", "footer")
                                put("d", buildJsonObject {
                                    put("sha256", digestStream.getHashHex())
                                })
                            }
                            writer.write(footer.toString() + "\n")
                        }
                    }
                }
            }
            
            val stats = verifyOrImportInternal(tmpFile, passphrase, isImport = false)
            if (!stats.verified) throw SecurityException("Verification of written export failed")
            
            if (!tmpFile.renameTo(file)) throw IOException("Failed to save final export file")
            
            return@withContext stats
        } finally {
            if (tmpFile.exists()) tmpFile.delete()
        }
    }

    override suspend fun verifyOrImport(file: File, passphrase: String, isImport: Boolean): ExportStats = withContext(Dispatchers.IO) {
        verifyOrImportInternal(file, passphrase, isImport)
    }

    private fun verifyOrImportInternal(file: File, passphrase: String, isImport: Boolean): ExportStats {
        FileInputStream(file).use { fis ->
            val headerBytes = ByteArray(106)
            var read = 0
            while(read < 106) {
                val r = fis.read(headerBytes, read, 106 - read)
                if (r == -1) throw SecurityException("File too short for header")
                read += r
            }
            
            val buf = ByteBuffer.wrap(headerBytes)
            val magic = ByteArray(4).apply { buf.get(this) }
            if (String(magic) != "MNEX") throw SecurityException("Invalid file magic")
            if (buf.short.toInt() != 1) throw SecurityException("Unsupported format version")
            buf.short // flags
            if (buf.get().toInt() != 1) throw SecurityException("Unsupported KDF")
            
            val mem = buf.int
            val iters = buf.int
            val par = buf.get().toInt()
            val salt = ByteArray(16).apply { buf.get(this) }
            val wrapNonce = ByteArray(12).apply { buf.get(this) }
            val wrappedDek = ByteArray(48).apply { buf.get(this) }
            buf.int 
            val noncePrefix = ByteArray(8).apply { buf.get(this) }
            
            val headerHash = MessageDigest.getInstance("SHA-256").digest(headerBytes)
            
            val kek = generateKek(passphrase, salt, mem, iters, par)
            val dek = unwrapKey(wrappedDek, kek, wrapNonce) 
            
            var msgCount = 0
            var nodeCount = 0
            var eventCount = 0
            
            val tempDbName = "mine_import_temp.db"
            var importHelper: SupportSQLiteOpenHelper? = null
            var db: SupportSQLiteDatabase? = null
            
            try {
                if (isImport) {
                    context.deleteDatabase(tempDbName)
                    importHelper = com.anchor.mine.storage.DatabaseProvider.getHelper(context, dbKey, tempDbName)
                    db = importHelper.writableDatabase
                    db.beginTransaction()
                }
                
                ChunkedAesInputStream(fis, dek, noncePrefix, headerHash).use { chunkedIn ->
                    GZIPInputStream(chunkedIn).use { gzipIn ->
                        val digestStream = MessageDigest.getInstance("SHA-256")
                        BufferedReader(InputStreamReader(gzipIn, Charsets.UTF_8)).use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                if (line!!.isBlank()) continue
                                val obj = Json.parseToJsonElement(line!!).jsonObject
                                val type = obj["t"]?.jsonPrimitive?.content
                                
                                if (type == "footer") {
                                    val expectedHash = obj["d"]?.jsonObject?.get("sha256")?.jsonPrimitive?.content
                                    val actualHash = digestStream.digest().joinToString("") { "%02x".format(it) }
                                    if (expectedHash != actualHash) throw SecurityException("Data corruption: Footer hash mismatch")
                                    break
                                }

                                digestStream.update(line!!.toByteArray(Charsets.UTF_8))
                                digestStream.update('\n'.code.toByte())

                                val data = obj["d"]?.jsonObject
                                when (type) {
                                    "raw_message" -> msgCount++
                                    "node" -> nodeCount++
                                    "raw_event" -> eventCount++
                                }
                                
                                if (isImport && type != null && data != null && type != "manifest") {
                                    val cols = data.keys.joinToString(",") { "\"$it\"" }
                                    val placeholders = data.keys.joinToString(",") { "?" }
                                    val vals = data.values.map { 
                                        if (it is JsonNull) null else it.jsonPrimitive.content 
                                    }.toTypedArray()
                                    db?.execSQL("INSERT INTO $type ($cols) VALUES ($placeholders)", vals)
                                }
                            }
                        }
                    }
                    if (!chunkedIn.isFinalSeen) throw SecurityException("Truncated or tampered file: missing final flag")
                }
                
                if (isImport) {
                    db?.setTransactionSuccessful()
                }
            } finally {
                if (isImport) {
                    db?.endTransaction()
                    importHelper?.close()
                    
                    // FILE SWAP: Safely overwrite the active DB file while it is closed
                    dbHelper.close()
                    
                    val originalFile = context.getDatabasePath("mine_encrypted.db")
                    val tempFile = context.getDatabasePath(tempDbName)
                    
                    tempFile.copyTo(originalFile, overwrite = true)
                    
                    // Nuke WAL and SHM files to prevent corruption from old cache
                    context.getDatabasePath("mine_encrypted.db-wal").delete()
                    context.getDatabasePath("mine_encrypted.db-shm").delete()
                    tempFile.delete()
                    context.getDatabasePath("mine_import_temp.db-wal").delete()
                    context.getDatabasePath("mine_import_temp.db-shm").delete()
                }
            }
            
            return ExportStats(msgCount, nodeCount, eventCount, verified = true)
        }
    }

    private fun exportTable(db: SupportSQLiteDatabase, table: String, writer: Writer, includeApiKeys: Boolean): Int {
        var count = 0
        db.query("SELECT * FROM $table").use { cursor ->
            val cols = cursor.columnNames
            while (cursor.moveToNext()) {
                // C3: Filter API keys if requested
                if (table == "app_meta" && !includeApiKeys) {
                    val key = cursor.getString(cursor.getColumnIndexOrThrow("key"))
                    if (key == "ep" || key == "key" || key == "mod") continue
                }
                
                val map = mutableMapOf<String, JsonElement>()
                for (i in cols.indices) {
                    if (!cursor.isNull(i)) {
                        when (cursor.getType(i)) {
                            Cursor.FIELD_TYPE_INTEGER -> map[cols[i]] = JsonPrimitive(cursor.getLong(i))
                            Cursor.FIELD_TYPE_FLOAT -> map[cols[i]] = JsonPrimitive(cursor.getDouble(i))
                            Cursor.FIELD_TYPE_STRING -> map[cols[i]] = JsonPrimitive(cursor.getString(i))
                        }
                    }
                }
                val row = buildJsonObject {
                    put("t", table)
                    put("v", 1)
                    put("d", JsonObject(map))
                }
                writer.write(row.toString() + "\n")
                count++
            }
        }
        return count
    }

    private fun generateKek(passphrase: String, salt: ByteArray, mem: Int = 65536, iters: Int = 3, par: Int = 1): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(iters).withMemoryAsKB(mem).withParallelism(par)
            .withSalt(salt).build()
        val gen = Argon2BytesGenerator().apply { init(params) }
        val kek = ByteArray(32)
        gen.generateBytes(passphrase.toByteArray(Charsets.UTF_8), kek)
        return kek
    }

    private fun wrapKey(dek: ByteArray, kek: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(128, nonce))
        return cipher.doFinal(dek)
    }

    private fun unwrapKey(wrapped: ByteArray, kek: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(128, nonce))
        return cipher.doFinal(wrapped) 
    }
}

class ChunkedAesOutputStream(
    private val out: OutputStream, private val dek: ByteArray, private val noncePrefix: ByteArray,
    private val headerHash: ByteArray, private val chunkSize: Int
) : OutputStream() {
    private val buffer = ByteArray(chunkSize)
    private var pos = 0
    private var chunkCounter = 0
    private var isClosed = false

    override fun write(b: Int) {
        buffer[pos++] = b.toByte()
        if (pos == chunkSize) flushChunk(false)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        var written = 0
        while (written < len) {
            val toWrite = minOf(chunkSize - pos, len - written)
            System.arraycopy(b, off + written, buffer, pos, toWrite)
            pos += toWrite; written += toWrite
            if (pos == chunkSize) flushChunk(false)
        }
    }

    private fun flushChunk(isFinal: Boolean) {
        if (pos == 0 && !isFinal) return
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = ByteBuffer.allocate(12).put(noncePrefix).putInt(chunkCounter).array()
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(128, nonce))
        val aad = ByteBuffer.allocate(37).put(headerHash).putInt(chunkCounter).put((if (isFinal) 1 else 0).toByte()).array()
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(buffer, 0, pos)
        
        out.write(ByteBuffer.allocate(4).putInt(ciphertext.size).array())
        out.write(ciphertext)
        pos = 0; chunkCounter++
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        flushChunk(true)
        out.close()
    }
}

class ChunkedAesInputStream(
    private val ins: InputStream, private val dek: ByteArray, private val noncePrefix: ByteArray,
    private val headerHash: ByteArray
) : InputStream() {
    private var buffer = ByteArray(0)
    private var pos = 0
    private var chunkCounter = 0
    var isFinalSeen = false
        private set

    override fun read(): Int {
        if (pos >= buffer.size && !loadNextChunk()) return -1
        return buffer[pos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        // C4: Loop until data or EOF, handling empty decrypted chunks if they occur
        var totalRead = 0
        while (totalRead < len) {
            if (pos >= buffer.size) {
                if (!loadNextChunk()) break
                if (buffer.isEmpty()) continue // Skip empty chunks
            }
            val toRead = minOf(len - totalRead, buffer.size - pos)
            System.arraycopy(buffer, pos, b, off + totalRead, toRead)
            pos += toRead
            totalRead += toRead
        }
        return if (totalRead == 0 && isFinalSeen) -1 else totalRead
    }

    private fun loadNextChunk(): Boolean {
        if (isFinalSeen) return false
        val lenBuf = ByteArray(4)
        var r = 0
        while(r < 4) {
            val c = ins.read(lenBuf, r, 4 - r)
            if (c == -1) {
                if (r == 0) throw SecurityException("Truncated: expected next chunk length")
                throw SecurityException("Truncated while reading chunk length")
            }
            r += c
        }
        val len = ByteBuffer.wrap(lenBuf).int
        val cipherBuf = ByteArray(len)
        r = 0
        while(r < len) {
            val c = ins.read(cipherBuf, r, len - r)
            if (c == -1) throw SecurityException("Truncated ciphertext")
            r += c
        }

        try {
            buffer = decrypt(cipherBuf, false)
        } catch (e: AEADBadTagException) {
            try {
                buffer = decrypt(cipherBuf, true)
                isFinalSeen = true
            } catch (e2: AEADBadTagException) {
                throw SecurityException("Chunk authentication failed (Tampered data)")
            }
        }
        pos = 0; chunkCounter++
        return true
    }

    private fun decrypt(ciphertext: ByteArray, isFinal: Boolean): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = ByteBuffer.allocate(12).put(noncePrefix).putInt(chunkCounter).array()
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(128, nonce))
        val aad = ByteBuffer.allocate(37).put(headerHash).putInt(chunkCounter).put((if (isFinal) 1 else 0).toByte()).array()
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }
}

class HashingOutputStream(private val out: OutputStream) : OutputStream() {
    private val digest = MessageDigest.getInstance("SHA-256")
    override fun write(b: Int) { digest.update(b.toByte()); out.write(b) }
    override fun write(b: ByteArray, off: Int, len: Int) { digest.update(b, off, len); out.write(b, off, len) }
    fun getHashHex() = digest.digest().joinToString("") { "%02x".format(it) }
    override fun close() = out.close()
}