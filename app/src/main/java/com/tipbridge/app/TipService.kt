package com.tipbridge.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// ---------------------------------------------------------------------------
// Shared state between the service and the screen
// ---------------------------------------------------------------------------

object Bus {
    private val main = Handler(Looper.getMainLooper())
    private val lines = ArrayList<String>()

    @Volatile var onChange: (() -> Unit)? = null
    @Volatile var running = false
    @Volatile var feed = "Not started"
    @Volatile var ble = "Not started"

    fun snapshotLog(): String = synchronized(lines) { lines.joinToString("\n") }

    fun log(msg: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        synchronized(lines) {
            lines.add(0, "$stamp  $msg")
            while (lines.size > 100) lines.removeAt(lines.size - 1)
        }
        changed()
    }

    fun changed() {
        main.post { onChange?.invoke() }
    }
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

/** rows = (minimum tokens, level 1-3, seconds) */
data class Config(val url: String, val maxLevel: Int, val rows: List<Triple<Int, Int, Int>>) {

    fun actionFor(tokens: Int): Pair<Int, Int>? {
        val r = rows.filter { tokens >= it.first }.maxByOrNull { it.first } ?: return null
        val level = minOf(r.second, maxLevel).coerceIn(1, 3)
        return Pair(level, r.third.coerceIn(1, 60))
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("tipbridge", Context.MODE_PRIVATE).edit()
            .putString("url", url)
            .putInt("maxLevel", maxLevel)
            .putString("rows", rows.joinToString(";") { "${it.first},${it.second},${it.third}" })
            .apply()
    }

    companion object {
        val DEFAULT_ROWS = listOf(
            Triple(1, 1, 5), Triple(10, 1, 10), Triple(50, 2, 20),
            Triple(100, 3, 30), Triple(500, 3, 60)
        )

        fun load(ctx: Context): Config {
            val p = ctx.getSharedPreferences("tipbridge", Context.MODE_PRIVATE)
            val parsed = p.getString("rows", null)?.split(";")?.mapNotNull { s ->
                val x = s.split(",")
                if (x.size == 3) {
                    val a = x[0].toIntOrNull()
                    val b = x[1].toIntOrNull()
                    val c = x[2].toIntOrNull()
                    if (a != null && b != null && c != null) Triple(a, b, c) else null
                } else null
            }
            val rows = if (parsed.isNullOrEmpty()) DEFAULT_ROWS else parsed
            return Config(p.getString("url", "") ?: "", p.getInt("maxLevel", 3), rows)
        }
    }
}

// ---------------------------------------------------------------------------
// Love Spouse / MuSe advertisement commands
// Format from the Buttplug project's protocol notes (docs.buttplug.io issue #2):
// 11 bytes of manufacturer data = 8-byte prefix + 3-byte command.
// ---------------------------------------------------------------------------

object LoveSpouse {
    const val COMPANY_ID = 0xFFF0

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private val PREFIX = bytes(0x6D, 0xB6, 0x43, 0xCE, 0x97, 0xFE, 0x42, 0x7C)

    val STOP: ByteArray = PREFIX + bytes(0xE5, 0x15, 0x7D)

    /** Index 0 = speed 1, 1 = speed 2, 2 = speed 3 (all channels). */
    val LEVELS: Array<ByteArray> = arrayOf(
        PREFIX + bytes(0xE4, 0x9C, 0x6C),
        PREFIX + bytes(0xE7, 0x07, 0x5E),
        PREFIX + bytes(0xE6, 0x8E, 0x4F)
    )
}

class Broadcaster(ctx: Context) {
    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var callback: AdvertiseCallback? = null

    fun problem(): String? {
        val a = adapter ?: return "This phone doesn't have Bluetooth."
        if (!a.isEnabled) return "Bluetooth is turned off. Turn it on and press START again."
        if (a.bluetoothLeAdvertiser == null) {
            return "This phone can't send the Bluetooth broadcasts the toy needs."
        }
        return null
    }

    @SuppressLint("MissingPermission")
    fun send(payload: ByteArray) {
        stop()
        try {
            Thread.sleep(120)
        } catch (ignored: InterruptedException) {
        }
        val adv = adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED) // about 250 ms
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(LoveSpouse.COMPANY_ID, payload)
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Bus.ble = "Broadcasting"
                Bus.changed()
            }

            override fun onStartFailure(errorCode: Int) {
                val why = when (errorCode) {
                    AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "data too large"
                    AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many broadcasts running"
                    AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "already started"
                    AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "internal error"
                    AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "this phone doesn't allow it"
                    else -> "code $errorCode"
                }
                Bus.ble = "Problem: $why"
                Bus.log("Bluetooth broadcast failed ($why).")
                Bus.changed()
            }
        }
        callback = cb
        advertiser = adv
        try {
            adv.startAdvertising(settings, data, cb)
        } catch (e: Exception) {
            Bus.ble = "Problem"
            Bus.log("Bluetooth broadcast failed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        try {
            callback?.let { advertiser?.stopAdvertising(it) }
        } catch (ignored: Exception) {
        }
        callback = null
    }
}

// ---------------------------------------------------------------------------
// The background service: watches Chaturbate and drives the toy
// ---------------------------------------------------------------------------

class TipService : Service() {

    companion object {
        const val ACTION_START = "com.tipbridge.app.START"
        const val ACTION_STOP = "com.tipbridge.app.STOP"
        const val ACTION_PANIC = "com.tipbridge.app.PANIC"
        const val ACTION_TEST = "com.tipbridge.app.TEST"
        private const val CHANNEL = "tipbridge"
    }

    private lateinit var broadcaster: Broadcaster
    private var cfg = Config("", 3, Config.DEFAULT_ROWS)
    private val actions = LinkedBlockingQueue<IntArray>(10) // [level, seconds]
    private val bleLock = Any()
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var alive = false
    @Volatile private var panic = false
    @Volatile private var busy = false
    @Volatile private var conn: HttpURLConnection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        broadcaster = Broadcaster(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Tip Bridge", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_PANIC -> if (alive) doPanic()
            ACTION_TEST -> if (alive) {
                Bus.log("Test: buzzing for 3 seconds.")
                enqueue(1, 3)
            }
            else -> begin()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        if (alive) return
        try {
            val n = buildNotification("Waiting for tips")
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(1, n)
            }
        } catch (e: Exception) {
            Bus.log("Couldn't start in the background: ${e.message}")
            stopSelf()
            return
        }

        cfg = Config.load(this)
        if (cfg.url.isBlank()) {
            Bus.log("No Chaturbate link saved. Paste your link and press START.")
            stopSelf()
            return
        }
        val problem = broadcaster.problem()
        if (problem != null) {
            Bus.log(problem)
            stopSelf()
            return
        }

        alive = true
        panic = false
        busy = false
        Bus.running = true
        Bus.feed = "Connecting..."
        Bus.ble = "Ready"
        Bus.log("Started. Waiting for tips.")

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TipBridge::run").also {
            it.acquire(12 * 60 * 60 * 1000L)
        }

        thread(isDaemon = true) { executorLoop() }
        thread(isDaemon = true) { pollLoop() }
    }

    override fun onDestroy() {
        val wasAlive = alive
        alive = false
        panic = true
        actions.clear()
        try {
            conn?.disconnect()
        } catch (ignored: Exception) {
        }
        if (wasAlive) {
            val b = broadcaster
            thread(isDaemon = true) {
                synchronized(bleLock) {
                    b.send(LoveSpouse.STOP)
                    try {
                        Thread.sleep(1500)
                    } catch (ignored: InterruptedException) {
                    }
                    b.stop()
                }
            }
        }
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (ignored: Exception) {
        }
        Bus.running = false
        Bus.feed = "Not started"
        Bus.ble = "Not started"
        if (wasAlive) Bus.log("Stopped.")
        Bus.changed()
        super.onDestroy()
    }

    // ---- tips -> actions ----

    private fun enqueue(level: Int, seconds: Int) {
        if (!actions.offer(intArrayOf(level, seconds))) {
            Bus.log("Lots of tips at once. Skipping one to keep things safe.")
        }
    }

    private fun handleEvent(ev: JSONObject) {
        val method = ev.optString("method")
        val obj = ev.optJSONObject("object") ?: return
        if (method == "tip") {
            val tokens = obj.optJSONObject("tip")?.optInt("tokens", 0) ?: 0
            val user = obj.optJSONObject("user")?.optString("username", "someone") ?: "someone"
            val action = cfg.actionFor(tokens)
            if (action == null) {
                Bus.log("$user tipped $tokens. That's below your lowest level.")
                return
            }
            Bus.log("$user tipped $tokens tokens: level ${action.first} for ${action.second}s")
            enqueue(action.first, action.second)
        } else if (method == "chatMessage") {
            val msg = obj.optJSONObject("message")?.optString("message", "")?.trim()?.lowercase() ?: ""
            val sender = obj.optJSONObject("user")?.optString("username", "") ?: ""
            if (msg == "!stop" && sender.isNotEmpty() && sender == obj.optString("broadcaster")) {
                doPanic()
            }
        }
    }

    // ---- the toy side ----

    private fun executorLoop() {
        while (alive) {
            val a: IntArray? = try {
                actions.poll(500, TimeUnit.MILLISECONDS)
            } catch (ignored: InterruptedException) {
                return
            }
            if (a == null) continue
            panic = false
            runAction(a[0], a[1])
        }
    }

    private fun runAction(level: Int, seconds: Int) {
        busy = true
        try {
            synchronized(bleLock) { broadcaster.send(LoveSpouse.LEVELS[level - 1]) }
            var waited = 0
            while (alive && !panic && waited < seconds * 1000) {
                Thread.sleep(100)
                waited += 100
            }
            if (!alive) return // onDestroy sends the stop command
            if (panic || actions.isEmpty()) finishVibration()
        } catch (ignored: InterruptedException) {
        } finally {
            busy = false
        }
    }

    private fun finishVibration() {
        synchronized(bleLock) {
            broadcaster.send(LoveSpouse.STOP)
            try {
                Thread.sleep(1500)
            } catch (ignored: InterruptedException) {
            }
            broadcaster.stop()
        }
        Bus.ble = "Ready"
        Bus.changed()
    }

    private fun doPanic() {
        panic = true
        actions.clear()
        Bus.log("Emergency stop.")
        if (!busy) thread(isDaemon = true) { finishVibration() }
    }

    // ---- the Chaturbate side ----

    private fun pause(ms: Long): Boolean {
        return try {
            Thread.sleep(ms)
            alive
        } catch (ignored: InterruptedException) {
            false
        }
    }

    private fun pollLoop() {
        var url = cfg.url
        var backoff = 1000L
        var connectedOnce = false
        while (alive) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = 15000
                c.readTimeout = 95000
                c.requestMethod = "GET"
                val code = c.responseCode
                if (code == 401 || code == 403 || code == 404) {
                    c.disconnect()
                    Bus.feed = "Problem"
                    Bus.log("Chaturbate rejected your link (error $code). Check it was copied correctly, or make a new one.")
                    Bus.changed()
                    if (!pause(15000)) return
                    continue
                }
                if (code != 200) throw IOException("error $code")
                val body = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()

                val json = JSONObject(body)
                if (Bus.feed != "Connected") {
                    Bus.feed = "Connected"
                    if (!connectedOnce) Bus.log("Connected to Chaturbate.")
                    connectedOnce = true
                    Bus.changed()
                }
                val events = json.optJSONArray("events")
                if (events != null) {
                    for (i in 0 until events.length()) handleEvent(events.getJSONObject(i))
                }
                val next = json.optString("nextUrl", "")
                if (next.isNotBlank()) url = next
                backoff = 1000L
            } catch (ignored: SocketTimeoutException) {
                continue // long-poll timeouts are normal
            } catch (e: Exception) {
                if (!alive) return
                Bus.feed = "Problem"
                Bus.log("Can't reach Chaturbate (${e.message}). Retrying...")
                Bus.changed()
                if (!pause(backoff)) return
                backoff = minOf(backoff * 2, 30000L)
            }
        }
    }

    // ---- notification ----

    @Suppress("DEPRECATION")
    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TipService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Tip Bridge is running")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .setOngoing(true)
            .build()
    }
}
