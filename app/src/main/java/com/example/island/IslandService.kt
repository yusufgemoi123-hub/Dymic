package com.example.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.CountDownTimer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

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
    private lateinit var root: FrameLayout
    private lateinit var pill: LinearLayout
    private lateinit var leftIcon: ImageView
    private lateinit var rightIcon: ImageView
    private lateinit var line1: TextView
    private lateinit var line2: TextView

    private var ready = false
    private var lastKind = ""

    // State
    private var recSec: Int? = null
    private var timerLeft: Int? = null
    private var timer: CountDownTimer? = null
    private var musicPkg: String? = null
    private var musicText: String? = null

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
        buildView()

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP }

        wm.addView(root, lp)
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

    private fun render() {
        val rec = recSec
        val tmr = timerLeft
        // Prioritas: Record > Timer > Musik
        val kind = when {
            rec != null -> "rec"
            tmr != null -> "timer"
            musicText != null -> "music"
            else -> "idle"
        }
        if (kind != lastKind) {
            TransitionManager.beginDelayedTransition(root, AutoTransition().setDuration(250))
            lastKind = kind
        }

        when (kind) {
            "rec" -> {
                pill.visibility = View.VISIBLE
                leftIcon.visibility = View.GONE
                rightIcon.visibility = View.GONE
                line1.setTextColor(Color.RED)
                line1.text = "Record detik ke-$rec •"
                line2.visibility = View.GONE
            }
            "timer" -> {
                pill.visibility = View.VISIBLE
                leftIcon.visibility = View.GONE
                rightIcon.visibility = View.VISIBLE
                line1.setTextColor(ORANGE)
                line1.text = "Timer"
                line2.visibility = View.VISIBLE
                line2.setTextColor(ORANGE)
                val t = tmr ?: 0
                line2.text = when {
                    t == 0 -> "Selesai"
                    t < 60 -> "$t dtk"
                    else -> "%02d:%02d".format(t / 60, t % 60)
                }
            }
            "music" -> {
                pill.visibility = View.VISIBLE
                leftIcon.visibility = View.VISIBLE
                rightIcon.visibility = View.GONE
                leftIcon.setImageDrawable(
                    try { packageManager.getApplicationIcon(musicPkg!!) } catch (e: Exception) { null }
                )
                line1.setTextColor(Color.WHITE)
                line1.text = musicText
                line2.visibility = View.GONE
            }
            else -> pill.visibility = View.GONE
        }
    }

    private val ORANGE = Color.parseColor("#FF9500")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildView() {
        root = FrameLayout(this)

        pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            minimumHeight = dp(36)
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(40).toFloat()
            }
            visibility = View.GONE
        }

        leftIcon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) }
        }

        fun label(sizeSp: Float) = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTypeface(typeface, Typeface.BOLD)
            maxWidth = dp(220)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        line1 = label(13f)
        line2 = label(12f)

        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(line1)
            addView(line2)
        }

        rightIcon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(10) }
            setImageResource(R.drawable.ic_timer)
        }

        pill.addView(leftIcon)
        pill.addView(center)
        pill.addView(rightIcon)

        root.addView(
            pill,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(8) }
        )
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
        if (ready) wm.removeView(root)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
