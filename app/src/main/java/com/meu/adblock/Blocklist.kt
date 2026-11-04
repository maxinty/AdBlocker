package com.meu.adblock

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Blocklist {
    @Volatile private var domains: Set<String> = emptySet()
    @Volatile var updating = false
        private set
    val size: Int get() = domains.size

    // Listas públicas de anúncios/rastreadores (formato hosts ou lista simples de domínios).
    private val SOURCES = listOf(
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/hosts/pro.txt",
        "https://raw.githubusercontent.com/blocklistproject/Lists/master/ads.txt",
        "https://raw.githubusercontent.com/Perflyst/PiHoleBlocklist/master/android-tracking.txt",
        "https://adaway.org/hosts.txt",
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    )

    private val WS = Regex("\\s+")
    private const val MAX_AGE_MS = 24L * 3600 * 1000

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "blocklist_cache_v2.txt")
    private fun customFile(ctx: Context) = File(ctx.filesDir, "custom_domains.txt")

    // Adiciona um domínio escolhido pelo usuário (vale na hora e fica salvo).
    fun addCustom(ctx: Context, domain: String): Boolean {
        val d = domain.trim().lowercase()
            .removePrefix("http://").removePrefix("https://")
            .substringBefore('/').trimEnd('.')
        if ('.' !in d || d.any { it.isWhitespace() }) return false
        customFile(ctx).appendText(d + "\n")
        domains = domains + d
        return true
    }

    // Lista embutida + lista baixada + domínios do usuário.
    fun load(ctx: Context) {
        val set = HashSet<String>()
        ctx.assets.open("blocklist.txt").bufferedReader().useLines { lines ->
            for (line in lines) {
                val t = line.trim().lowercase()
                if (t.isNotEmpty() && !t.startsWith("#")) set.add(t)
            }
        }
        for (f in listOf(cacheFile(ctx), customFile(ctx))) {
            if (f.exists()) f.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val t = line.trim()
                    if (t.isNotEmpty()) set.add(t)
                }
            }
        }
        domains = set
    }

    private fun parse(line: String): String? {
        val l = line.substringBefore('#').trim()
        if (l.isEmpty() || l[0] == '!') return null
        val p = l.split(WS)
        val raw = if (p.size >= 2 && (p[0] == "0.0.0.0" || p[0] == "127.0.0.1")) p[1]
                  else if (p.size == 1) p[0]
                  else return null
        val d = raw.lowercase()
        if (d == "localhost" || '.' !in d) return null
        if (d.all { it.isDigit() || it == '.' }) return null          // ignora IPs
        if (!d.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) return null
        return d
    }

    // Baixa as listas (renova a cada 24h, ou na hora com force = true).
    fun update(ctx: Context, force: Boolean = false) {
        if (updating) return
        val f = cacheFile(ctx)
        if (!force && f.exists() && System.currentTimeMillis() - f.lastModified() < MAX_AGE_MS) return
        updating = true
        try {
            val set = HashSet<String>()
            var ok = 0
            for (u in SOURCES) {
                try {
                    val c = URL(u).openConnection() as HttpURLConnection
                    c.connectTimeout = 15000
                    c.readTimeout = 60000
                    c.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            val d = parse(line)
                            if (d != null) set.add(d)
                        }
                    }
                    ok++
                } catch (_: Exception) {}
            }
            if (ok > 0 && set.size > 1000) {
                val tmp = File(ctx.filesDir, "blocklist_cache_v2.tmp")
                tmp.bufferedWriter().use { w ->
                    for (d in set) { w.write(d); w.write("\n") }
                }
                tmp.renameTo(f)
                File(ctx.filesDir, "blocklist_cache.txt").delete()   // cache antigo
                load(ctx)
            }
        } catch (_: Exception) {
        } finally {
            updating = false
        }
    }

    // Bloqueia o domínio e todos os subdomínios.
    fun isBlocked(name: String): Boolean {
        val set = domains
        var d = name.lowercase().trimEnd('.')
        while (true) {
            if (d in set) return true
            val i = d.indexOf('.')
            if (i < 0) return false
            d = d.substring(i + 1)
        }
    }
}
