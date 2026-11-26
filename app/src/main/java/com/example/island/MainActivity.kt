package com.example.island

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("island", MODE_PRIVATE)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        fun title(t: String) {
            col.addView(TextView(this).apply {
                text = t
                textSize = 16f
                setPadding(0, 40, 0, 8)
            })
        }

        fun row(vararg items: Pair<String, () -> Unit>) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((label, act) in items) {
                val b = Button(this)
                b.text = label
                b.setOnClickListener { act() }
                r.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            col.addView(r)
        }

        fun bump(key: String, d: Int) {
            prefs.edit().putInt(key, prefs.getInt(key, 0) + d).apply()
        }

        title("Persiapan (sekali aja)")
        row("1. Izin overlay" to {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        row("2. Izin akses notifikasi (musik)" to {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        row("3. Matikan hemat baterai app ini" to {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        })
        row("4. Nyalakan Island" to {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            IslandService.send(this, IslandService.ACTION_START)
        })

        title("Tes tampilan")
        row(
            "Mulai record" to { IslandService.send(this, IslandService.ACTION_START_RECORD) },
            "Stop record" to { IslandService.send(this, IslandService.ACTION_STOP_RECORD) }
        )
        row(
            "Timer 30 dtk" to { IslandService.send(this, IslandService.ACTION_START_TIMER, 30) },
            "Stop timer" to { IslandService.send(this, IslandService.ACTION_STOP_TIMER) }
        )

        title("Kalibrasi posisi (kalau belum pas sama kamera)")
        row(
            "◀ Kiri" to { bump("ox", -1) },
            "Kanan ▶" to { bump("ox", 1) }
        )
        row(
            "▲ Atas" to { bump("oy", -1) },
            "▼ Bawah" to { bump("oy", 1) }
        )
        row(
            "Kecil −" to { bump("ex", -1) },
            "Besar +" to { bump("ex", 1) }
        )
        row("Reset kalibrasi" to { prefs.edit().clear().apply() })

        setContentView(ScrollView(this).apply { addView(col) })
    }
}
