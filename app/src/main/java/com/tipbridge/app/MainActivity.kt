package com.tipbridge.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private val GREEN = Color.parseColor("#2E9E4F")
        private val RED = Color.parseColor("#D33030")
        private val GREY = Color.parseColor("#888888")
        private val ORANGE = Color.parseColor("#E0A000")
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }

    private lateinit var urlEdit: EditText
    private lateinit var maxSpinner: Spinner
    private val rows = ArrayList<Array<EditText>>()
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var panicBtn: Button
    private lateinit var testBtn: Button
    private lateinit var feedText: TextView
    private lateinit var bleText: TextView
    private lateinit var logText: TextView
    private var pendingStart = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(s: String, size: Float = 14f, bold: Boolean = false, color: Int = Color.DKGRAY) =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun section(root: LinearLayout, title: String) {
        val t = tv(title, 15f, true, Color.BLACK)
        t.setPadding(0, dp(18), 0, dp(4))
        root.addView(t)
    }

    private fun btn(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setBackgroundColor(color)
        setOnClickListener { onClick() }
    }

    private fun setBtn(b: Button, enabled: Boolean) {
        b.isEnabled = enabled
        b.alpha = if (enabled) 1f else 0.4f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val cfg = Config.load(this)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(28))
        }
        scroll.addView(root, LinearLayout.LayoutParams(MATCH, WRAP))

        root.addView(tv("Tip Bridge", 26f, true, Color.BLACK))
        root.addView(tv("Tips on your Chaturbate room control your toy.", 13f))

        // 1. link
        section(root, "1. Your private Chaturbate link")
        urlEdit = EditText(this).apply {
            hint = "Paste your link here"
            setText(cfg.url)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(urlEdit, LinearLayout.LayoutParams(MATCH, WRAP))
        val showBox = CheckBox(this).apply {
            text = "Show link"
            setOnCheckedChangeListener { _, checked ->
                urlEdit.inputType = InputType.TYPE_CLASS_TEXT or
                    (if (checked) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    else InputType.TYPE_TEXT_VARIATION_PASSWORD)
            }
        }
        root.addView(showBox)
        root.addView(tv("Keep this link secret. Anyone who has it can see your room activity.", 12f, false, Color.parseColor("#A05000")))

        // 2. max level
        section(root, "2. Strongest the toy is ever allowed to go")
        maxSpinner = Spinner(this)
        maxSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("Level 1 (gentlest)", "Level 2", "Level 3 (strongest)")
        )
        maxSpinner.setSelection((cfg.maxLevel - 1).coerceIn(0, 2))
        root.addView(maxSpinner, LinearLayout.LayoutParams(MATCH, WRAP))

        // 3. tip table
        section(root, "3. What each tip does")
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (h in arrayOf("Tip of at least (tokens)", "Level (1-3)", "Seconds")) {
            val t = tv(h, 12f, true, Color.BLACK)
            t.gravity = Gravity.CENTER
            header.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        root.addView(header)
        for (i in 0 until 5) {
            val r = cfg.rows.getOrNull(i)
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val cells = Array(3) { c ->
                EditText(this).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    gravity = Gravity.CENTER
                    isSingleLine = true
                    if (r != null) {
                        setText(
                            when (c) {
                                0 -> r.first.toString()
                                1 -> r.second.toString()
                                else -> r.third.toString()
                            }
                        )
                    }
                }
            }
            for (cell in cells) line.addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f))
            rows.add(cells)
            root.addView(line)
        }
        root.addView(tv("Leave a row empty to skip it.", 12f))

        // buttons
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startBtn = btn("START", GREEN) { onStart() }
        stopBtn = btn("STOP", Color.parseColor("#555555")) { onStop() }
        bar.addView(startBtn, LinearLayout.LayoutParams(0, dp(64), 1f).apply { setMargins(0, 0, dp(6), 0) })
        bar.addView(stopBtn, LinearLayout.LayoutParams(0, dp(64), 1f))
        val barParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(20), 0, 0) }
        root.addView(bar, barParams)

        panicBtn = btn("EMERGENCY STOP", RED) { onPanic() }
        root.addView(panicBtn, LinearLayout.LayoutParams(MATCH, dp(56)).apply { setMargins(0, dp(8), 0, 0) })

        testBtn = btn("Test: buzz for 3 seconds", Color.parseColor("#3D6FB6")) { onTest() }
        root.addView(testBtn, LinearLayout.LayoutParams(MATCH, dp(48)).apply { setMargins(0, dp(8), 0, 0) })

        // status
        section(root, "Status")
        feedText = tv("", 15f)
        bleText = tv("", 15f)
        root.addView(feedText)
        root.addView(bleText)

        // phone tips
        section(root, "Keep it running")
        root.addView(
            tv(
                "While streaming, keep the phone plugged in, keep this app open, and keep the phone " +
                    "close to the toy. If the phone closes the app by itself, open the battery settings " +
                    "below and allow Tip Bridge to run without limits.",
                12f
            )
        )
        val batt = btn("Open battery settings", Color.parseColor("#777777")) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (ignored: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
        root.addView(batt, LinearLayout.LayoutParams(MATCH, dp(44)).apply { setMargins(0, dp(8), 0, 0) })

        // log
        section(root, "What's happening")
        logText = tv("", 12f).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(logText)

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        Bus.onChange = { refresh() }
        refresh()
    }

    override fun onPause() {
        Bus.onChange = null
        super.onPause()
    }

    // ---- display ----

    private fun refresh() {
        val running = Bus.running
        urlEdit.isEnabled = !running
        maxSpinner.isEnabled = !running
        for (r in rows) for (e in r) e.isEnabled = !running
        setBtn(startBtn, !running)
        setBtn(stopBtn, running)
        setBtn(panicBtn, running)
        setBtn(testBtn, running)
        setStatus(feedText, "Chaturbate", Bus.feed)
        setStatus(bleText, "Toy broadcast", Bus.ble)
        logText.text = Bus.snapshotLog()
    }

    private fun setStatus(t: TextView, name: String, value: String) {
        val color = when {
            value == "Connected" || value == "Ready" || value == "Broadcasting" -> GREEN
            value.startsWith("Problem") -> RED
            value == "Not started" -> GREY
            else -> ORANGE
        }
        t.text = "\u25CF $name: $value"
        t.setTextColor(color)
    }

    // ---- actions ----

    private fun collectConfig(): Config? {
        val url = urlEdit.text.toString().trim()
        if (!url.startsWith("http")) {
            Toast.makeText(this, "Please paste your Chaturbate link (it starts with https://).", Toast.LENGTH_LONG).show()
            return null
        }
        val list = ArrayList<Triple<Int, Int, Int>>()
        for (r in rows) {
            val a = r[0].text.toString().trim()
            val b = r[1].text.toString().trim()
            val c = r[2].text.toString().trim()
            if (a.isEmpty() && b.isEmpty() && c.isEmpty()) continue
            val x = a.toIntOrNull()
            val y = b.toIntOrNull()
            val z = c.toIntOrNull()
            if (x == null || y == null || z == null) {
                Toast.makeText(this, "Each tip row needs three whole numbers, or leave it empty.", Toast.LENGTH_LONG).show()
                return null
            }
            list.add(Triple(x, y.coerceIn(1, 3), z.coerceIn(1, 60)))
        }
        if (list.isEmpty()) {
            Toast.makeText(this, "Please fill in at least one tip row.", Toast.LENGTH_LONG).show()
            return null
        }
        return Config(url, maxSpinner.selectedItemPosition + 1, list)
    }

    private fun neededPermissions(): Array<String> {
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)
        return need.toTypedArray()
    }

    private fun onStart() {
        val cfg = collectConfig() ?: return
        cfg.save(this)
        val need = neededPermissions()
        if (need.isNotEmpty()) {
            pendingStart = true
            requestPermissions(need, 1)
            return
        }
        launchService()
    }

    private fun launchService() {
        startForegroundService(Intent(this, TipService::class.java).setAction(TipService.ACTION_START))
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!pendingStart) return
        pendingStart = false
        val advertiseOk = Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        if (advertiseOk) {
            launchService()
        } else {
            Toast.makeText(
                this,
                "Tip Bridge needs the Bluetooth ('Nearby devices') permission to talk to the toy.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun onStop() {
        stopService(Intent(this, TipService::class.java))
    }

    private fun onPanic() {
        if (Bus.running) startService(Intent(this, TipService::class.java).setAction(TipService.ACTION_PANIC))
    }

    private fun onTest() {
        if (Bus.running) {
            startService(Intent(this, TipService::class.java).setAction(TipService.ACTION_TEST))
        } else {
            Toast.makeText(this, "Press START first.", Toast.LENGTH_SHORT).show()
        }
    }
}
