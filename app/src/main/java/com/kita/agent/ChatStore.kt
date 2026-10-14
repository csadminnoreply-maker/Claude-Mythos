package com.kita.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Riwayat percakapan di penyimpanan aplikasi. Tetap ada setelah aplikasi ditutup. */
class ChatStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "chats").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")

    data class Meta(
        val id: String,
        val title: String,
        val updated: Long,
        val preview: String,
        val apk: String
    )

    @Synchronized
    fun list(): List<Meta> {
        val arr = readIndex()
        val out = ArrayList<Meta>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Meta(
                    o.optString("id"),
                    o.optString("title").ifBlank { "Percakapan" },
                    o.optLong("updated"),
                    o.optString("preview"),
                    o.optString("apk")
                )
            )
        }
        return out.sortedByDescending { it.updated }
    }

    @Synchronized
    fun save(id: String, title: String, items: List<Hist>, apk: String) {
        if (id.isBlank()) return
        val arr = JSONArray()
        for (h in items) arr.put(histToJson(h))
        var body = JSONObject().put("id", id).put("title", title).put("apk", apk).put("items", arr)
        if (body.toString().length > 1_800_000) {
            val slim = JSONArray()
            for (h in items) {
                val o = histToJson(h)
                o.remove("raw")
                val results = o.optJSONArray("results")
                if (results != null) {
                    for (i in 0 until results.length()) {
                        val r = results.optJSONObject(i) ?: continue
                        val out = r.optString("output")
                        if (out.length > 4000) r.put("output", out.takeLast(4000))
                    }
                }
                slim.put(o)
            }
            body = JSONObject().put("id", id).put("title", title).put("apk", apk).put("items", slim)
        }
        atomicWrite(File(dir, "$id.json"), body.toString())
        val preview = items.lastOrNull { it.role == 0 || (it.role == 1 && it.text.isNotBlank()) }?.text ?: ""
        val idx = readIndex()
        var found = false
        for (i in 0 until idx.length()) {
            val o = idx.optJSONObject(i) ?: continue
            if (o.optString("id") == id) {
                o.put("title", title)
                o.put("updated", System.currentTimeMillis())
                o.put("preview", preview.take(140))
                o.put("apk", apk)
                found = true
                break
            }
        }
        if (!found) {
            idx.put(
                JSONObject()
                    .put("id", id)
                    .put("title", title)
                    .put("updated", System.currentTimeMillis())
                    .put("preview", preview.take(140))
                    .put("apk", apk)
            )
        }
        atomicWrite(indexFile, idx.toString())
    }

    @Synchronized
    fun load(id: String): Pair<List<Hist>, String>? {
        val f = File(dir, "$id.json")
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            val arr = o.optJSONArray("items") ?: JSONArray()
            val items = ArrayList<Hist>()
            for (i in 0 until arr.length()) {
                val h = jsonToHist(arr.optJSONObject(i) ?: continue)
                items.add(h)
            }
            Pair(items, o.optString("apk"))
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun delete(id: String) {
        File(dir, "$id.json").delete()
        val idx = readIndex()
        val next = JSONArray()
        for (i in 0 until idx.length()) {
            val o = idx.optJSONObject(i) ?: continue
            if (o.optString("id") != id) next.put(o)
        }
        atomicWrite(indexFile, next.toString())
    }

    /** Tulis ke file sementara lalu rename, supaya crash tidak merusak riwayat. */
    private fun atomicWrite(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }

    private fun readIndex(): JSONArray {
        if (!indexFile.exists()) return JSONArray()
        return try {
            JSONArray(indexFile.readText())
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun histToJson(h: Hist): JSONObject {
        val o = JSONObject().put("role", h.role).put("text", h.text)
        if (h.calls.isNotEmpty()) {
            val arr = JSONArray()
            for (c in h.calls) {
                arr.put(JSONObject().put("id", c.id).put("name", c.name).put("args", c.args.toString()))
            }
            o.put("calls", arr)
        }
        if (h.results.isNotEmpty()) {
            val arr = JSONArray()
            for ((c, out) in h.results) {
                arr.put(
                    JSONObject().put("id", c.id).put("name", c.name).put("args", c.args.toString()).put("output", out)
                )
            }
            o.put("results", arr)
        }
        if (h.raw != null) o.put("raw", h.raw.toString())
        return o
    }

    private fun jsonToHist(o: JSONObject): Hist {
        val calls = ArrayList<Call>()
        val carr = o.optJSONArray("calls")
        if (carr != null) {
            for (i in 0 until carr.length()) {
                val c = carr.optJSONObject(i) ?: continue
                calls.add(Call(c.optString("id"), c.optString("name"), parseObj(c.optString("args"))))
            }
        }
        val results = ArrayList<Pair<Call, String>>()
        val rarr = o.optJSONArray("results")
        if (rarr != null) {
            for (i in 0 until rarr.length()) {
                val r = rarr.optJSONObject(i) ?: continue
                results.add(
                    Pair(
                        Call(r.optString("id"), r.optString("name"), parseObj(r.optString("args"))),
                        r.optString("output")
                    )
                )
            }
        }
        val rawStr = o.optString("raw")
        val raw = if (rawStr.isBlank()) null else try {
            JSONObject(rawStr)
        } catch (e: Exception) {
            null
        }
        return Hist(o.optInt("role"), o.optString("text"), calls, results, raw)
    }

    private fun parseObj(s: String): JSONObject =
        try {
            JSONObject(if (s.isBlank()) "{}" else s)
        } catch (e: Exception) {
            JSONObject()
        }
}
