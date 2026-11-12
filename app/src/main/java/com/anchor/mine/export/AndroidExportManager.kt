// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.export

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.anchor.core.export.ExportManager
import com.anchor.core.export.ExportStats
import com.anchor.core.time.DayRollover
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
    private var dbHelper: SupportSQLiteOpenHelper,
    private val dbKey: ByteArray,
    private val liveDbName: String = "mine_encrypted.db",
    private val scheduler: com.anchor.core.alarms.AlarmScheduler? = null,
    private val clock: com.anchor.core.time.Clock = com.anchor.core.time.SystemClock(),
    private val reconciler: com.anchor.core.alarms.AlarmReconciler? = null,
    // H-04 test seam. The default is the real copy; tests override it to fail the second copy
    // (temp -> live), which is the crash window the .bak rollback exists for. Failure of the
    // first copy (live -> .bak) cannot be injected through the file system on a real device.
    private val copyFile: (src: File, dst: File) -> Unit = { src, dst -> src.copyTo(dst, overwrite = true) }
) : ExportManager {

    companion object {
        val EXPORTED_TABLES = setOf(
            "raw_message",
            "raw_event",
            "node",
            "edge",
            "soul_fact",
            "app_meta",
            "day_rule",
            "reminder",
            "proposal",
            "quote_slot",
            "learned_pref",
            "signal_log",
            "place",
            "day_digest"
        )

        internal fun generateKek(passphrase: String, salt: ByteArray, mem: Int = 65536, iters: Int = 3, par: Int = 1): ByteArray {
            val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(iters).withMemoryAsKB(mem).withParallelism(par)
                .withSalt(salt).build()
            val gen = Argon2BytesGenerator().apply { init(params) }
            val kek = ByteArray(32)
            gen.generateBytes(passphrase.toByteArray(Charsets.UTF_8), kek)
            return kek
        }

        internal fun wrapKey(dek: ByteArray, kek: ByteArray, nonce: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(128, nonce))
            return cipher.doFinal(dek)
        }
    }

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
                            exportTable(db, "quote_slot", writer, includeApiKeys)
                            exportTable(db, "learned_pref", writer, includeApiKeys)
                            exportTable(db, "signal_log", writer, includeApiKeys)
                            exportTable(db, "place", writer, includeApiKeys)
                            exportTable(db, "day_digest", writer, includeApiKeys)
                            
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

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun verifyOrImportInternal(file: File, passphrase: String, isImport: Boolean): ExportStats {
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
            buf.short
            if (buf.get().toInt() != 1) throw SecurityException("Unsupported KDF")

            val mem = buf.int
            val iters = buf.int
            val par = buf.get().toInt()
            if (mem <= 0 || mem > 131072 || iters !in 1..10 || par !in 1..4) {
                throw SecurityException("KDF parameters out of safe bounds: mem=$mem, iters=$iters, par=$par")
            }
            val salt = ByteArray(16).apply { buf.get(this) }
            val wrapNonce = ByteArray(12).apply { buf.get(this) }
            val wrappedDek = ByteArray(48).apply { buf.get(this) }
            val chunkSize = buf.int
            if (chunkSize !in 1024..(16 * 1024 * 1024)) {
                throw SecurityException("Invalid chunkSize in header: $chunkSize")
            }
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
            var dbTransactionActive = false

            try {
                if (isImport) {
                    context.deleteDatabase(tempDbName)
                    importHelper = com.anchor.mine.storage.DatabaseProvider.getHelper(context, dbKey, tempDbName)
                    db = importHelper.writableDatabase
                    db.beginTransaction()
                    dbTransactionActive = true
                }

                // Pre-fetch column whitelist for all valid exported tables from fresh temp DB
                val validColumnsPerTable = mutableMapOf<String, Set<String>>()
                if (isImport && db != null) {
                    for (tName in EXPORTED_TABLES) {
                        val colSet = mutableSetOf<String>()
                        db.query("PRAGMA table_info(\"$tName\")").use { cursor ->
                            val nameColIdx = cursor.getColumnIndexOrThrow("name")
                            while (cursor.moveToNext()) {
                                colSet.add(cursor.getString(nameColIdx))
                            }
                        }
                        validColumnsPerTable[tName] = colSet
                    }
                }

                ChunkedAesInputStream(fis, dek, noncePrefix, headerHash, chunkSize).use { chunkedIn ->
                    java.util.zip.GZIPInputStream(chunkedIn).use { gzipIn ->
                        val digestStream = java.security.MessageDigest.getInstance("SHA-256")
                        java.io.BufferedReader(java.io.InputStreamReader(gzipIn, kotlin.text.Charsets.UTF_8)).use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                val currentLine = line ?: break
                                if (currentLine.isBlank()) continue
                                val obj = Json.parseToJsonElement(currentLine).jsonObject
                                val type = obj["t"]?.jsonPrimitive?.content

                                if (type == "footer") {
                                    val expectedHash = obj["d"]?.jsonObject?.get("sha256")?.jsonPrimitive?.content
                                    val actualHash = digestStream.digest().joinToString("") { "%02x".format(it) }
                                    if (expectedHash != actualHash) throw SecurityException("Data corruption: Footer hash mismatch")
                                    break
                                }

                                digestStream.update(currentLine.toByteArray(Charsets.UTF_8))
                                digestStream.update('\n'.code.toByte())

                                if (type == "manifest") continue

                                if (type !in EXPORTED_TABLES) {
                                    throw SecurityException("Disallowed or unknown table in export: $type")
                                }

                                val data = obj["d"]?.jsonObject ?: throw SecurityException("Missing data object for table $type")
                                when (type) {
                                    "raw_message" -> msgCount++
                                    "node" -> nodeCount++
                                    "raw_event" -> eventCount++
                                }

                                if (isImport && db != null) {
                                    val validCols = validColumnsPerTable[type]
                                        ?: throw SecurityException("Unknown table schema: $type")
                                    for (colKey in data.keys) {
                                        if (colKey !in validCols) {
                                            throw SecurityException("Disallowed or unknown column '$colKey' for table '$type'")
                                        }
                                    }
                                    val cols = data.keys.joinToString(",") { "\"$it\"" }
                                    val placeholders = data.keys.joinToString(",") { "?" }
                                    val vals = data.values.map {
                                        if (it is JsonNull) null else it.jsonPrimitive.content
                                    }.toTypedArray()
                                    db.execSQL("INSERT INTO \"$type\" ($cols) VALUES ($placeholders)", vals)
                                }
                            }
                        }
                    }
                    // SEC 11: Consume stream to end to ensure final chunk is read
                    while (chunkedIn.read() != -1) {}
                    if (!chunkedIn.isFinalSeen) throw SecurityException("Truncated or tampered file: missing final flag")
                }

                if (isImport) {
                    db?.setTransactionSuccessful()
                    db?.endTransaction()
                    dbTransactionActive = false
                    importHelper?.close()
                    importHelper = null

                    // H-04 / R-10: SAFE SWAP WITH ROLLBACK. Verification covers the file, not the
                    // swap. The swap used to be a single copyTo over the live file, so a process
                    // death or I/O error mid-copy left the live DB truncated with the SQLCipher key
                    // still in place - unopenable, history gone, no backup. The live file is now
                    // copied to a .bak first and the live DB is re-opened and integrity-checked
                    // after the swap; any failure restores the .bak and rethrows.
                    dbHelper.close()
                    val originalFile = context.getDatabasePath(liveDbName)
                    val tempFile = context.getDatabasePath(tempDbName)
                    val bakFile = context.getDatabasePath("$liveDbName.bak")
                    // A .bak left by an earlier interrupted swap is the last known-good copy; only
                    // discard it once we have a fresh one.
                    bakFile.delete()
                    if (originalFile.exists()) copyFile(originalFile, bakFile)

                    try {
                        copyFile(tempFile, originalFile)

                        context.getDatabasePath("$liveDbName-wal").delete()
                        context.getDatabasePath("$liveDbName-shm").delete()
                        tempFile.delete()
                        context.getDatabasePath("$tempDbName-wal").delete()
                        context.getDatabasePath("$tempDbName-shm").delete()

                        // The copy is only trusted once the swapped DB opens and passes an
                        // integrity check under the live key.
                        val verifyHelper = com.anchor.mine.storage.DatabaseProvider.getHelper(context, dbKey, liveDbName)
                        try {
                            verifyHelper.readableDatabase.query("PRAGMA quick_check").use { cursor ->
                                if (!cursor.moveToFirst() || cursor.getString(0) != "ok") {
                                    throw IOException("Imported database failed integrity check")
                                }
                            }
                        } finally {
                            verifyHelper.close()
                        }

                        // B4: Rebuild derived msg_chunk rows from the imported raw_message rows.
                        // This lives INSIDE the rollback window: a rebuild failure used to leave
                        // the live DB holding imported rows with the .bak already deleted, so the
                        // pre-import history was gone and the import was half-applied. The rebuild
                        // derives day_key/ts_utc for rows that lack them, so a sparse export row
                        // cannot abort the import on msg_chunk's NOT NULL constraints.
                        rebuildMsgChunks()

                        // TB-4: Rebind live database helper and MineApp.sharedStorage so the running
                        // process never hits IllegalStateException: attempt to re-open an already-closed object
                        rebindLiveDatabase()
                    } catch (e: Exception) {
                        // Roll back to the pre-import DB. If the restore itself fails there is
                        // nothing safer to do than surface both failures.
                        if (bakFile.exists()) {
                            try {
                                copyFile(bakFile, originalFile)
                                context.getDatabasePath("$liveDbName-wal").delete()
                                context.getDatabasePath("$liveDbName-shm").delete()
                                rebindLiveDatabase()
                            } catch (restoreError: Exception) {
                                e.addSuppressed(restoreError)
                            }
                        } else {
                            try {
                                rebindLiveDatabase()
                            } catch (rebindError: Exception) {
                                e.addSuppressed(rebindError)
                            }
                        }
                        throw e
                    }

                    // Swap verified and the rebuild succeeded: the .bak is no longer needed.
                    bakFile.delete()

                    // Post-import alarm reconciliation
                    if (reconciler != null) {
                        reconciler.reconcile(force = true)
                    } else if (scheduler != null) {
                        val postImportStorage = if (liveDbName == "mine_encrypted.db") {
                            (context.applicationContext as? com.anchor.mine.MineApp)?.let { app ->
                                if (app.sharedStorage.isCompleted) {
                                    try {
                                        app.sharedStorage.getCompleted()
                                    } catch (_: Throwable) {
                                        null
                                    }
                                } else null
                            } ?: com.anchor.mine.storage.AndroidStorage(this.dbHelper)
                        } else {
                            val helper = com.anchor.mine.storage.DatabaseProvider.getHelper(context, dbKey, liveDbName)
                            try {
                                val storage = com.anchor.mine.storage.AndroidStorage(helper)
                                val postReconciler = com.anchor.core.alarms.AlarmReconciler(storage, scheduler, clock)
                                postReconciler.reconcile(force = true)
                            } finally {
                                helper.close()
                            }
                            null
                        }
                        if (postImportStorage != null) {
                            val postReconciler = com.anchor.core.alarms.AlarmReconciler(postImportStorage, scheduler, clock)
                            postReconciler.reconcile(force = true)
                        }
                    }
                }
            } catch (e: Exception) {
                if (dbTransactionActive) db?.endTransaction()
                throw e
            } finally {
                importHelper?.close()
                context.getDatabasePath(tempDbName).delete()
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

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun rebindLiveDatabase() {
        if (liveDbName != "mine_encrypted.db") {
            // Isolated test database (e.g. ExportTest): do not touch or rebind MineApp.sharedStorage
            return
        }
        val newHelper = com.anchor.mine.storage.DatabaseProvider.getHelper(context, dbKey, liveDbName)
        this.dbHelper = newHelper
        val app = context.applicationContext as? com.anchor.mine.MineApp
        if (app != null) {
            val existingStorage = if (app.sharedStorage.isCompleted) {
                try {
                    app.sharedStorage.getCompleted()
                } catch (_: Throwable) {
                    null
                }
            } else null
            if (existingStorage is com.anchor.mine.storage.AndroidStorage) {
                existingStorage.rebind(newHelper)
            } else {
                val newStorage = com.anchor.mine.storage.AndroidStorage(newHelper)
                val newDef = kotlinx.coroutines.CompletableDeferred<com.anchor.core.storage.Storage>()
                newDef.complete(newStorage)
                app.sharedStorage = newDef
            }
        }
    }

    /**
     * B4: Rebuild derived msg_chunk rows on the live DB after the import swap.
     * The temp/import DB never contains msg_chunk rows (it is excluded from
     * EXPORTED_TABLES as derived data), so after the swap the live DB's chunks
     * would otherwise not match its imported raw_message rows. A full clear +
     * rebuild from raw_message keeps repeated imports idempotent and mirrors
     * AndroidStorage.insertRawMessage's chunking rule (one chunk per message,
     * skipping blank, system-role, and redacted rows). The existing
     * trg_msg_chunk_fts_insert/delete triggers keep the FTS index in sync.
     */
    private fun rebuildMsgChunks() {
        val helper = com.anchor.mine.storage.DatabaseProvider.getHelper(context, dbKey, liveDbName)
        try {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM msg_chunk")
                val now = System.currentTimeMillis()
                val rule = db.query(
                    "SELECT id, effective_from, start_minute, end_minute, tz_id, created_at " +
                        "FROM day_rule WHERE effective_from <= ? ORDER BY effective_from DESC LIMIT 1",
                    arrayOf<Any>(now)
                ).use {
                    if (it.moveToFirst()) {
                        com.anchor.core.models.DayRule(
                            id = it.getString(0),
                            effectiveFrom = it.getLong(1),
                            startMinute = it.getInt(2),
                            endMinute = it.getInt(3),
                            tzId = it.getString(4),
                            createdAt = it.getLong(5)
                        )
                    } else {
                        DayRollover.defaultRule(now)
                    }
                }
                db.query(
                    "SELECT id, day_key, ts_utc, role, content FROM raw_message " +
                        "WHERE content IS NOT NULL AND TRIM(content) != '' AND role != 'system'"
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0)
                        // An imported row may omit day_key/ts_utc (they are nullable on
                        // raw_message but NOT NULL on msg_chunk). Derive them the same way
                        // insertRawMessage does rather than aborting the whole import.
                        val ts = if (cursor.isNull(2)) System.currentTimeMillis() else cursor.getLong(2)
                        val dayKey = if (cursor.isNull(1)) {
                            DayRollover.computeDayKey(ts, rule)
                        } else {
                            cursor.getString(1)
                        }
                        val content = cursor.getString(4)
                        db.execSQL(
                            "INSERT INTO msg_chunk (id, day_key, ts_start, ts_end, msg_ids, text, extractor_version) VALUES (?, ?, ?, ?, ?, ?, ?)",
                            arrayOf<Any>("chunk_$id", dayKey, ts, ts, "[\"$id\"]", content, "v1")
                        )
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            helper.close()
        }
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
    private val ins: InputStream,
    private val dek: ByteArray,
    private val noncePrefix: ByteArray,
    private val headerHash: ByteArray,
    private val chunkSize: Int = 1024 * 1024
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
        if (len < 16 || len > chunkSize + 16) {
            throw SecurityException("Invalid chunk length: $len (expected 16..${chunkSize + 16})")
        }
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