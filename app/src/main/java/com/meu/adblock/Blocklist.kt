package com.meu.adblock

import android.content.Context

object Blocklist {
    private var domains: Set<String> = emptySet()

    fun load(ctx: Context) {
        domains = ctx.assets.open("blocklist.txt").bufferedReader().useLines { lines ->
            lines.map { it.trim().lowercase() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toHashSet()
        }
    }

    // Bloqueia o domínio e todos os subdomínios.
    fun isBlocked(name: String): Boolean {
        var d = name.lowercase().trimEnd('.')
        while (true) {
            if (d in domains) return true
            val i = d.indexOf('.')
            if (i < 0) return false
            d = d.substring(i + 1)
        }
    }
}
