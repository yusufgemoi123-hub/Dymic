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

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        fun btn(label: String, onClick: () -> Unit) {
            col.addView(Button(this).apply {
                text = label
                setOnClickListener { onClick() }
            })
        }

        btn("1. Izin overlay (tampil di atas app lain)") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        btn("2. Izin akses notifikasi (buat deteksi musik)") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        btn("3. Nyalakan Island") {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            IslandService.send(this, IslandService.ACTION_START)
        }
        btn("Mulai record") { IslandService.send(this, IslandService.ACTION_START_RECORD) }
        btn("Stop record") { IslandService.send(this, IslandService.ACTION_STOP_RECORD) }
        btn("Timer 30 detik") {
            IslandService.send(this, IslandService.ACTION_START_TIMER, 30)
        }
        btn("Stop timer") { IslandService.send(this, IslandService.ACTION_STOP_TIMER) }

        setContentView(ScrollView(this).apply { addView(col) })
    }
}
