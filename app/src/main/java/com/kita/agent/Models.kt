package com.kita.agent

import org.json.JSONObject

class Call(val id: String, val name: String, val args: JSONObject)

/** Riwayat netral: role 0 = pengguna, 1 = model, 2 = hasil tool. */
class Hist(
    val role: Int,
    val text: String = "",
    val calls: List<Call> = emptyList(),
    val results: List<Pair<Call, String>> = emptyList(),
    val raw: JSONObject? = null
)

internal class Preset(val name: String, val fmt: String, val base: String, val model: String)

internal class StoppedException : RuntimeException("Dihentikan")

internal class TransientException(msg: String) : RuntimeException(msg)
