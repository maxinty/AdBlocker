package com.meu.adblock

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
    private lateinit var btn: Button
    private lateinit var list: ListView
    private var apps = listOf<Pair<String, String>>()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val prefs = getSharedPreferences("p", MODE_PRIVATE)
        val saved = prefs.getStringSet("apps", emptySet())!!
        val pm = packageManager
        apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 96, 32, 32) }
        root.addView(TextView(this).apply {
            text = "Marque os apps em que os anúncios serão bloqueados:"
            textSize = 16f
        })
        list = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_MULTIPLE
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_multiple_choice,
                apps.map { it.second })
        }
        apps.forEachIndexed { i, a -> list.setItemChecked(i, a.first in saved) }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        btn = Button(this).apply { gravity = Gravity.CENTER; setOnClickListener { toggle() } }
        root.addView(btn)
        setContentView(root)
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() { btn.text = if (AdVpnService.running) "PARAR BLOQUEIO" else "INICIAR BLOQUEIO" }

    private fun toggle() {
        if (AdVpnService.running) {
            startService(Intent(this, AdVpnService::class.java).setAction(AdVpnService.ACTION_STOP))
            btn.postDelayed({ refresh() }, 300)
            return
        }
        val sel = apps.filterIndexed { i, _ -> list.isItemChecked(i) }.map { it.first }.toSet()
        if (sel.isEmpty()) { Toast.makeText(this, "Escolha ao menos um app", Toast.LENGTH_SHORT).show(); return }
        getSharedPreferences("p", MODE_PRIVATE).edit().putStringSet("apps", sel).apply()
        val i = VpnService.prepare(this)
        if (i != null) startActivityForResult(i, 1) else startVpn()
    }

    private fun startVpn() {
        startService(Intent(this, AdVpnService::class.java))
        btn.postDelayed({ refresh() }, 500)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (req == 1 && res == RESULT_OK) startVpn()
    }
}
