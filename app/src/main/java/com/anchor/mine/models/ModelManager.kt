// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.models

import android.content.Context
import com.anchor.core.voice.ModelCatalog
import com.anchor.mine.voice.SherpaVoiceEngine
import com.mine.companion.store.ModelUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Downloads, verifies and deletes on-device models (blueprint 60 §3.4).
 *
 * - Files go to `files/models/<installDir>/` via a `.part` file, resumed with HTTP Range after
 *   a dropped connection.
 * - Every large file is checked against the SHA-256 Hugging Face publishes for it (the Git LFS
 *   object id, read from the repo's tree API at download time); a mismatch deletes the file and
 *   marks the model failed. Small text files are checked by size. Direct files use the pinned hash.
 * - A `.complete` marker is written last, so a half-downloaded model never counts as installed.
 * - Wi-Fi only unless the user allowed mobile data.
 */
class ModelManager(private val context: Context, private val scope: CoroutineScope) {

    private val root = File(context.filesDir, "models")
    private val jobs = ConcurrentHashMap<String, Job>()
    private val _state = MutableStateFlow<Map<String, ModelUi>>(emptyMap())
    val state: StateFlow<Map<String, ModelUi>> = _state.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    init { refresh() }

    fun dirOf(e: ModelCatalog.Entry) = File(root, e.installDir)

    fun isInstalled(e: ModelCatalog.Entry): Boolean {
        val dir = dirOf(e)
        // Models side-loaded before the manager existed have no marker but all their files.
        return e.requiredFiles.all { File(dir, it).exists() } && (File(dir, MARKER).exists() || e.kind != com.anchor.core.voice.ModelAssetKind.EMBED)
    }

    fun refresh(activeEmbedder: String? = null, activeTts: String? = null) {
        _state.update { cur ->
            ModelCatalog.ALL.associate { e ->
                val prev = cur[e.id]
                val downloading = jobs[e.id]?.isActive == true
                e.id to ModelUi(
                    state = when {
                        downloading -> "downloading"
                        isInstalled(e) -> "ready"
                        prev?.state == "failed" -> "failed"
                        else -> "absent"
                    },
                    progress = if (downloading) prev?.progress ?: 0f else 0f,
                    bytesOnDisk = if (isInstalled(e)) sizeOf(dirOf(e)) else 0L,
                    error = if (downloading) null else prev?.error,
                    active = e.id == activeEmbedder || e.id == activeTts,
                )
            }
        }
    }

    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
        _state.update { it + (id to (it[id] ?: ModelUi()).copy(state = "absent", progress = 0f)) }
    }

    fun delete(id: String) {
        val e = ModelCatalog.byId(id) ?: return
        cancel(id)
        dirOf(e).deleteRecursively()
        if (e.kind != com.anchor.core.voice.ModelAssetKind.EMBED) SherpaVoiceEngine.reload()
        refresh()
    }

    /** Starts a download. [onDone] runs after a verified install (reload engines, re-index). */
    fun download(id: String, allowMobileData: Boolean, onDone: (ModelCatalog.Entry) -> Unit = {}) {
        val e = ModelCatalog.byId(id) ?: return
        if (!e.runnable) return // listed for information only (see the entry's note)
        if (jobs[id]?.isActive == true) return
        if (!allowMobileData && isMetered()) {
            setFailed(id, "You're on mobile data. Connect to Wi-Fi, or allow mobile data for downloads.")
            return
        }
        jobs[id] = scope.launch(Dispatchers.IO) {
            _state.update { it + (id to ModelUi(state = "downloading", progress = 0f)) }
            try {
                val files = plan(e)
                val total = files.sumOf { it.size }.coerceAtLeast(1L)
                var doneBytes = 0L
                val dir = dirOf(e).apply { mkdirs() }
                File(dir, MARKER).delete()
                for (f in files) {
                    if (!isActive) return@launch
                    val dest = File(dir, f.localPath)
                    dest.parentFile?.mkdirs()
                    if (!(dest.exists() && dest.length() == f.size && (f.sha256 == null || sha256(dest) == f.sha256))) {
                        fetch(f, dest) { got -> setProgress(id, (doneBytes + got).toFloat() / total) }
                    }
                    doneBytes += f.size
                    setProgress(id, doneBytes.toFloat() / total)
                }
                File(dir, MARKER).writeText("${e.id}\n${System.currentTimeMillis()}\n")
                if (e.kind != com.anchor.core.voice.ModelAssetKind.EMBED) SherpaVoiceEngine.reload()
                jobs.remove(id)
                refresh()
                onDone(e)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                jobs.remove(id)
                setFailed(id, plain(t))
            }
        }
    }

    // ---------------------------------------------------------------------------------------

    private data class PlannedFile(val url: String, val localPath: String, val size: Long, val sha256: String?)

    /** Resolves the catalog entry to concrete URLs, sizes and expected hashes. */
    private fun plan(e: ModelCatalog.Entry): List<PlannedFile> = when (val s = e.source) {
        is ModelCatalog.Source.Direct -> listOf(PlannedFile(s.url, s.fileName, s.sizeBytes, s.sha256))
        is ModelCatalog.Source.HuggingFace -> {
            val out = ArrayList<PlannedFile>()
            var url: String? = "https://huggingface.co/api/models/${s.repo}/tree/${s.revision}?recursive=true"
            while (url != null) {
                val req = Request.Builder().url(url).header("accept", "application/json").build()
                val next = http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("Hugging Face listing failed (HTTP ${resp.code})")
                    val arr = json.parseToJsonElement(resp.body?.string().orEmpty()) as? JsonArray ?: throw IOException("Unexpected listing")
                    for (item in arr) {
                        val o = item.jsonObject
                        if (o["type"]?.jsonPrimitive?.contentOrNull != "file") continue
                        val path = o["path"]?.jsonPrimitive?.contentOrNull ?: continue
                        if (!ModelCatalog.includes(s.include, path)) continue
                        val size = o["size"]?.jsonPrimitive?.longOrNull ?: continue
                        val lfs = o["lfs"] as? JsonObject
                        val sha = lfs?.get("oid")?.jsonPrimitive?.contentOrNull
                        val enc = path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
                        out += PlannedFile("https://huggingface.co/${s.repo}/resolve/${s.revision}/$enc", ModelCatalog.localName(s, path), lfs?.get("size")?.jsonPrimitive?.longOrNull ?: size, sha)
                    }
                    // Pagination: Link: <url>; rel="next"
                    resp.header("link")?.let { Regex("""<([^>]+)>;\s*rel="next"""").find(it)?.groupValues?.get(1) }
                }
                url = next
            }
            val missing = s.include.filter { inc -> out.none { pf -> if (inc.endsWith("/")) true else ModelCatalog.localName(s, inc) == pf.localPath } && !inc.endsWith("/") }
            if (missing.isNotEmpty()) throw IOException("The model repository no longer has: ${missing.joinToString()}")
            out
        }
    }

    private fun fetch(f: PlannedFile, dest: File, onBytes: (Long) -> Unit) {
        val part = File(dest.path + ".part")
        var have = if (part.exists()) part.length() else 0L
        if (have > f.size) { part.delete(); have = 0 }
        val req = Request.Builder().url(f.url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 416) { part.delete(); throw IOException("Download restarted; try again") }
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for ${dest.name}")
            val append = resp.code == 206
            if (!append) have = 0
            val body = resp.body ?: throw IOException("Empty response")
            java.io.FileOutputStream(part, append).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var got = have
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        if (got - lastReport > 512 * 1024) { onBytes(got); lastReport = got }
                    }
                }
            }
        }
        if (part.length() != f.size) throw IOException("${dest.name}: expected ${f.size} bytes, got ${part.length()}")
        if (f.sha256 != null) {
            val actual = sha256(part)
            if (!actual.equals(f.sha256, ignoreCase = true)) {
                part.delete()
                throw IOException("${dest.name} failed its checksum and was deleted")
            }
        }
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) throw IOException("Couldn't move ${dest.name} into place")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isMetered(): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.isActiveNetworkMetered
    } catch (_: Throwable) { false }

    private fun sizeOf(f: File): Long = if (f.isFile) f.length() else f.listFiles()?.sumOf { sizeOf(it) } ?: 0L

    private fun setProgress(id: String, p: Float) =
        _state.update { it + (id to (it[id] ?: ModelUi()).copy(state = "downloading", progress = p.coerceIn(0f, 1f))) }

    private fun setFailed(id: String, msg: String) =
        _state.update { it + (id to (it[id] ?: ModelUi()).copy(state = "failed", progress = 0f, error = msg)) }

    private fun plain(t: Throwable): String = when (t) {
        is java.net.UnknownHostException -> "No internet connection."
        is java.net.SocketTimeoutException -> "The download stalled. Try again — it resumes where it stopped."
        else -> t.message ?: t.javaClass.simpleName
    }

    companion object {
        const val MARKER = ".complete"
    }
}
