package com.kita.agent

/**
 * Auto-approval hanya untuk perintah yang benar-benar membaca.
 * Semua yang lain (tulis, hapus, pindah, build, instal, shell lain) wajib konfirmasi.
 * Jika ragu, kembalikan false.
 */
object CommandPolicy {
    private val readOnly = setOf(
        "ls", "pwd", "cat", "head", "tail", "less", "more", "wc", "file", "stat",
        "du", "df", "whoami", "id", "date", "uname", "printenv", "which",
        "type", "true", "false", "echo", "printf", "grep", "egrep", "fgrep", "rg",
        "nl", "sort", "uniq", "cut", "diff", "cmp", "md5sum", "sha1sum", "sha256sum",
        "sha512sum", "basename", "dirname", "realpath", "readlink", "tree", "column",
        "fold", "tr", "comm", "od", "hexdump", "xxd", "strings", "tac", "rev",
        "paste", "join", "cd", "dirs", "test", "[", "expr", "seq", "clear", "tty",
        "groups", "hostname", "arch", "nproc", "uptime"
    )

    fun isReadOnly(raw: String): Boolean {
        val cmd = raw.trim()
        if (cmd.isEmpty() || cmd.length > 8000) return false
        val parts = splitSegments(cmd) ?: return false
        if (parts.isEmpty()) return false
        return parts.all { segmentIsReadOnly(it) }
    }

    private fun splitSegments(cmd: String): List<String>? {
        val parts = ArrayList<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var i = 0
        fun flush() {
            val t = cur.toString().trim()
            if (t.isNotEmpty()) parts.add(t)
            cur.setLength(0)
        }
        while (i < cmd.length) {
            val c = cmd[i]
            if (quote != null) {
                if (quote == '"') {
                    // Substitusi perintah tetap dieksekusi di dalam kutip ganda.
                    if (c == '`') return null
                    if (c == '$' && i + 1 < cmd.length && cmd[i + 1] == '(') return null
                }
                cur.append(c)
                if (c == '\\' && quote == '"' && i + 1 < cmd.length) {
                    cur.append(cmd[i + 1])
                    i += 2
                    continue
                }
                if (c == quote) quote = null
                i++
                continue
            }
            when (c) {
                '\\' -> {
                    if (i + 1 >= cmd.length) return null
                    cur.append(c)
                    cur.append(cmd[i + 1])
                    i += 2
                }
                '\'', '"' -> {
                    quote = c
                    cur.append(c)
                    i++
                }
                '`' -> return null
                '<' -> {
                    if (i + 1 < cmd.length && cmd[i + 1] == '(') return null
                    cur.append(c)
                    i++
                }
                '$' -> {
                    if (i + 1 < cmd.length && cmd[i + 1] == '(') return null
                    cur.append(c)
                    i++
                }
                '\n', ';' -> {
                    flush()
                    i++
                }
                '&' -> {
                    if (i + 1 < cmd.length && cmd[i + 1] == '&') {
                        flush()
                        i += 2
                    } else return null
                }
                '|' -> {
                    if (i + 1 < cmd.length && cmd[i + 1] == '|') {
                        flush()
                        i += 2
                    } else if (i + 1 < cmd.length && cmd[i + 1] == '&') {
                        return null
                    } else {
                        flush()
                        i++
                    }
                }
                else -> {
                    cur.append(c)
                    i++
                }
            }
        }
        if (quote != null) return null
        flush()
        return parts
    }

    /** Null jika ada redirect tulis di luar kutipan. 2>&1 dan >&1 diabaikan. */
    private fun stripRedirects(seg: String): String? {
        val out = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < seg.length) {
            val c = seg[i]
            if (quote != null) {
                out.append(c)
                if (c == '\\' && quote == '"' && i + 1 < seg.length) {
                    out.append(seg[i + 1])
                    i += 2
                    continue
                }
                if (c == quote) quote = null
                i++
                continue
            }
            if (c == '\'' || c == '"') {
                quote = c
                out.append(c)
                i++
                continue
            }
            if (c == '2' && seg.startsWith("2>&1", i)) {
                out.append(' ')
                i += 4
                continue
            }
            if (c == '>' && seg.startsWith(">&1", i)) {
                out.append(' ')
                i += 3
                continue
            }
            if (c == '>') return null
            out.append(c)
            i++
        }
        if (quote != null) return null
        return out.toString()
    }

    private fun tokenize(seg: String): List<String>? {
        val toks = ArrayList<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var i = 0
        fun flush() {
            if (cur.isNotEmpty()) {
                toks.add(cur.toString())
                cur.setLength(0)
            }
        }
        while (i < seg.length) {
            val c = seg[i]
            if (quote != null) {
                if (c == quote) {
                    quote = null
                    i++
                    continue
                }
                if (c == '\\' && quote == '"' && i + 1 < seg.length) {
                    cur.append(seg[i + 1])
                    i += 2
                    continue
                }
                cur.append(c)
                i++
                continue
            }
            when (c) {
                '\'', '"' -> {
                    quote = c
                    i++
                }
                ' ', '\t' -> {
                    flush()
                    i++
                }
                else -> {
                    cur.append(c)
                    i++
                }
            }
        }
        if (quote != null) return null
        flush()
        return toks
    }

    private fun segmentIsReadOnly(rawSeg: String): Boolean {
        val cleaned = stripRedirects(rawSeg) ?: return false
        val tokens = tokenize(cleaned) ?: return false
        if (tokens.isEmpty()) return false
        var i = 0
        if (tokens[0].matches(Regex("^[A-Za-z_][A-Za-z0-9_]*=.*"))) return false
        val first = tokens[i]
        if (first.contains('/') && !first.startsWith("/data/data/com.termux/files/usr/bin/")) return false
        var bin = first.substringAfterLast('/')
        if (bin == "sudo" || bin == "doas" || bin.startsWith("-") || bin.startsWith(".")) return false
        if (bin.contains("gradle")) return false
        if (bin == "command" || bin == "builtin") {
            if (i + 1 >= tokens.size) return false
            i++
            bin = tokens[i].substringAfterLast('/')
            if (bin.startsWith(".") || bin.contains("gradle")) return false
        }
        val args = tokens.drop(i + 1)
        if (bin == "find") return findIsSafe(args)
        if (bin == "git") return gitIsSafe(args)
        if (bin !in readOnly) return false
        return flagsAreSafe(bin, args)
    }

    /** Beberapa perintah "baca" punya opsi yang menulis file atau menjalankan program lain. */
    private fun flagsAreSafe(bin: String, args: List<String>): Boolean {
        fun has(vararg f: String) = args.any { a -> f.any { a == it || a.startsWith("$it=") } }
        val positional = args.filter { !it.startsWith("-") }
        return when (bin) {
            "sort" -> !(has("-o", "--output", "--compress-program") || args.any { it.startsWith("-o") && it.length > 2 && !it.startsWith("--") })
            "uniq" -> positional.size <= 1
            "tree" -> !has("-o")
            "xxd" -> positional.size <= 1
            "rg" -> !has("--pre", "--hostname-bin")
            "file" -> !has("-C", "--compile")
            "hostname" -> positional.isEmpty()
            "date" -> !has("-s", "--set")
            "less", "more" -> false
            else -> true
        }
    }

    private fun findIsSafe(args: List<String>): Boolean {
        val bad = setOf(
            "-delete", "-exec", "-execdir", "-ok", "-okdir", "-fls",
            "-fprint", "-fprint0", "-fprintf"
        )
        return args.none { a -> a in bad || a.startsWith("-exec") || a.startsWith("-ok") }
    }

    private fun gitIsSafe(args: List<String>): Boolean {
        if (args.isEmpty()) return true
        val sub = args[0]
        if (sub == "--version" || sub == "--help" || sub == "-v") {
            return args.size == 1 || gitIsSafe(args.drop(1))
        }
        if (sub == "-C" || sub == "--git-dir" || sub == "--work-tree") {
            if (args.size < 3) return false
            return gitIsSafe(args.drop(2))
        }
        if (sub.startsWith("-")) return false
        if (args.any { it == "--output" || it.startsWith("--output=") || it == "--ext-diff" || it == "--textconv" ||
                it == "-O" || it.startsWith("-O") && sub == "grep" || it == "--open-files-in-pager" ||
                it.startsWith("--open-files-in-pager=") || it == "--exec-path" }) return false
        val safe = setOf(
            "status", "log", "diff", "show", "rev-parse", "ls-files", "describe",
            "blame", "shortlog", "grep", "version", "help"
        )
        if (sub == "reflog") {
            val a = args.getOrNull(1)
            return a == null || a == "show" || a == "--date=iso" || a.startsWith("-")
        }
        if (sub == "remote") {
            val a = args.getOrNull(1)
            return a == null || a == "-v" || a == "show" || a == "get-url"
        }
        if (sub == "branch") {
            if (args.drop(1).any { !it.startsWith("-") }) return false
            return args.drop(1).none {
                it == "-d" || it == "-D" || it == "-m" || it == "-M" || it == "-c" || it == "-C" ||
                    it == "--delete" || it.startsWith("--delete")
            }
        }
        if (sub == "tag") {
            if (args.drop(1).any { !it.startsWith("-") }) return false
            return args.drop(1).none { it == "-d" || it == "-a" || it == "-s" || it == "-f" || it == "--delete" }
        }
        if (sub == "stash") {
            val a = args.getOrNull(1)
            return a == null || a == "list" || a == "show"
        }
        if (sub == "config") {
            val a = args.getOrNull(1) ?: return false
            return a == "--get" || a == "--list" || a == "-l" || a == "--get-regexp"
        }
        return sub in safe
    }
}
