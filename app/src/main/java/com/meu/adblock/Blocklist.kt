package com.meu.adblock

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Blocklist {
    @Volatile private var domains: Set<String> = emptySet()
    val size: Int get() = domains.size

    private val SOURCES = listOf(
        "https://adaway.org/hosts.txt",
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    )

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "blocklist_cache.txt")
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

    // Lista embutida + lista baixada (se existir).
    fun load(ctx: Context) {
        val set = HashSet<String>()
        ctx.assets.open("blocklist.txt").bufferedReader().useLines { lines ->
            for (line in lines) {
                val t = line.trim().lowercase()
                if (t.isNotEmpty() && !t.startsWith("#")) set.add(t)
            }
        }
        val f = cacheFile(ctx)
        if (f.exists()) f.bufferedReader().useLines { lines ->
            for (line in lines) {
                val t = line.trim()
                if (t.isNotEmpty()) set.add(t)
            }
        }
        val c = customFile(ctx)
        if (c.exists()) c.bufferedReader().useLines { lines ->
            for (line in lines) {
                val t = line.trim()
                if (t.isNotEmpty()) set.add(t)
            }
        }
        domains = set
    }

    // Baixa listas grandes de anúncios (renova a cada 3 dias).
    fun update(ctx: Context) {
        val f = cacheFile(ctx)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 3L * 24 * 3600 * 1000) return
        val set = HashSet<String>()
        for (u in SOURCES) {
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 30000
                c.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val l = line.substringBefore('#').trim()
                        if (l.isEmpty()) continue
                        val p = l.split(Regex("\\s+"))
                        if (p.size >= 2 && (p[0] == "0.0.0.0" || p[0] == "127.0.0.1")) {
                            val d = p[1].lowercase()
                            if (d != "localhost" && '.' in d) set.add(d)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        if (set.size > 1000) {
            f.writeText(set.joinToString("\n"))
            load(ctx)
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
