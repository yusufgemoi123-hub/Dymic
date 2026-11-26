package com.example.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager

class IslandService : Service() {

    companion object {
        var instance: IslandService? = null

        const val ACTION_START = "island.START"
        const val ACTION_START_RECORD = "island.START_RECORD"
        const val ACTION_STOP_RECORD = "island.STOP_RECORD"
        const val ACTION_START_TIMER = "island.START_TIMER"
        const val ACTION_STOP_TIMER = "island.STOP_TIMER"
        const val EXTRA_SECONDS = "seconds"

        fun send(ctx: Context, action: String, seconds: Int = 0) {
            val i = Intent(ctx, IslandService::class.java)
                .setAction(action)
                .putExtra(EXTRA_SECONDS, seconds)
            ctx.startForegroundService(i)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var island: IslandView
    private var ready = false

    private val prefs: SharedPreferences by lazy { getSharedPreferences("island", MODE_PRIVATE) }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (ready) applyCalibration()
    }
    private val iconCache = HashMap<String, Drawable?>()

    // State
    private var recSec: Int? = null
    private var timerLeft: Int? = null
    private var timer: CountDownTimer? = null
    private var musicPkg: String? = null
    private var musicText: String? = null

    private val orange = Color.parseColor("#FF9500")

    private val recTick = object : Runnable {
        override fun run() {
            recSec = (recSec ?: 0) + 1
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForeground(1, buildNotification())

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        island = IslandView(this)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.density * 170).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        wm.addView(island, lp)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        applyCalibration()
        ready = true
        render()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!ready) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_START_RECORD -> {
                handler.removeCallbacks(recTick)
                recSec = 0
                render()
                handler.postDelayed(recTick, 1000)
            }
            ACTION_STOP_RECORD -> {
                handler.removeCallbacks(recTick)
                recSec = null
                render()
            }
            ACTION_START_TIMER -> startTimer(intent.getIntExtra(EXTRA_SECONDS, 30))
            ACTION_STOP_TIMER -> {
                timer?.cancel()
                timerLeft = null
                render()
            }
        }
        return START_STICKY
    }

    // Dipanggil MusicListener
    fun setMusic(pkg: String?, text: String?) {
        if (!ready) return
        if (pkg == musicPkg && text == musicText) return
        musicPkg = pkg
        musicText = text
        render()
    }

    private fun applyCalibration() {
        island.setCalibration(
            prefs.getInt("ox", 0).toFloat(),
            prefs.getInt("oy", 0).toFloat(),
            prefs.getInt("ex", 0).toFloat()
        )
    }

    private fun startTimer(seconds: Int) {
        timer?.cancel()
        timerLeft = seconds
        render()
        timer = object : CountDownTimer(seconds * 1000L, 1000) {
            override fun onTick(ms: Long) {
                timerLeft = ((ms + 500) / 1000).toInt()
                render()
            }

            override fun onFinish() {
                timerLeft = 0
                render()
                handler.postDelayed({
                    timerLeft = null
                    render()
                }, 2500)
            }
        }.start()
    }

    private fun icon(pkg: String?): Drawable? {
        if (pkg == null) return null
        return iconCache.getOrPut(pkg) {
            try {
                packageManager.getApplicationIcon(pkg)
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun render() {
        val rec = recSec
        val tmr = timerLeft
        // Prioritas: Record > Timer > Musik
        val model = when {
            rec != null -> IslandModel(
                Kind.REC,
                rowText = "Record detik ke-$rec •",
                color = Color.RED
            )
            tmr != null -> IslandModel(
                Kind.TIMER,
                leftText = "Timer",
                timerIcon = true,
                rowText = when {
                    tmr == 0 -> "Selesai"
                    tmr < 60 -> "$tmr dtk"
                    else -> "%02d:%02d".format(tmr / 60, tmr % 60)
                },
                color = orange
            )
            musicText != null -> IslandModel(
                Kind.MUSIC,
                leftIcon = icon(musicPkg),
                eq = true,
                rowText = musicText,
                color = Color.WHITE
            )
            else -> IslandModel(Kind.IDLE)
        }
        island.render(model)
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("island", "Island", NotificationManager.IMPORTANCE_MIN)
        )
        return Notification.Builder(this, "island")
            .setContentTitle("Island aktif")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    override fun onDestroy() {
        instance = null
        timer?.cancel()
        handler.removeCallbacksAndMessages(null)
        if (ready) {
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
            wm.removeView(island)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
