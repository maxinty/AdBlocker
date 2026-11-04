package com.meu.adblock

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    private lateinit var btn: Button
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var logAdapter: ArrayAdapter<String>
    private var lastLog: List<String> = emptyList()
    private var apps = listOf<Pair<String, String>>()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000) }
    }

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

        // 1) Lista de apps
        root.addView(TextView(this).apply {
            text = "Marque os apps em que os anúncios serão bloqueados:"
            textSize = 15f
        })
        list = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_MULTIPLE
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_multiple_choice,
                apps.map { it.second })
        }
        apps.forEachIndexed { i, a -> list.setItemChecked(i, a.first in saved) }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        // 2) Contadores
        status = TextView(this).apply { textSize = 11f; setPadding(0, 12, 0, 4) }
        root.addView(status)

        // 3) Log de consultas: toque num domínio ✓ para bloquear
        root.addView(TextView(this).apply {
            text = "Consultas recentes (toque em um ✓ para bloquear):"
            textSize = 11f
        })
        logAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, ArrayList<String>()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.textSize = 12f
                v.setPadding(8, 6, 8, 6)
                return v
            }
        }
        val logList = ListView(this).apply {
            adapter = logAdapter
            setOnItemClickListener { _, _, pos, _ ->
                val item = logAdapter.getItem(pos) ?: return@setOnItemClickListener
                if (item.startsWith("✓ ")) {
                    val d = item.substring(2)
                    if (Blocklist.addCustom(this@MainActivity, d)) {
                        Toast.makeText(this@MainActivity, "Bloqueado: $d", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@MainActivity, "Esse já está bloqueado", Toast.LENGTH_SHORT).show()
                }
            }
        }
        root.addView(logList, LinearLayout.LayoutParams(-1, 0, 1f))

        // 4) Digitar um domínio manualmente
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val et = EditText(this).apply {
            hint = "Bloquear domínio (ex.: ads.site.com)"
            textSize = 13f
            setSingleLine()
        }
        val addBtn = Button(this).apply {
            text = "BLOQUEAR"
            setOnClickListener {
                if (Blocklist.addCustom(this@MainActivity, et.text.toString())) {
                    et.setText("")
                    Toast.makeText(this@MainActivity, "Domínio bloqueado", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Domínio inválido", Toast.LENGTH_SHORT).show()
                }
            }
        }
        row.addView(et, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(addBtn)
        root.addView(row)

        // 5) Botões principais
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btn = Button(this).apply { gravity = Gravity.CENTER; setOnClickListener { toggle() } }
        val upd = Button(this).apply {
            text = "ATUALIZAR LISTAS"
            setOnClickListener {
                Toast.makeText(this@MainActivity, "Baixando listas...", Toast.LENGTH_SHORT).show()
                val ctx = applicationContext
                Thread { Blocklist.update(ctx, true) }.start()
            }
        }
        buttons.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(upd, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)

        setContentView(root)
        refresh()
    }

    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }

    private fun refresh() {
        val running = AdVpnService.running
        btn.text = if (running) "PARAR BLOQUEIO" else "INICIAR BLOQUEIO"
        val upd = if (Blocklist.updating) " (atualizando...)" else ""
        status.text = if (running)
            "Lista: ${Blocklist.size} domínios$upd | Consultas: ${AdVpnService.queries.get()} | Bloqueadas: ${AdVpnService.blockedCount.get()}"
        else "Parado$upd"
        val items = AdVpnService.recent.toList()
        if (items != lastLog) {
            lastLog = items
            logAdapter.clear()
            logAdapter.addAll(items)
        }
    }

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
