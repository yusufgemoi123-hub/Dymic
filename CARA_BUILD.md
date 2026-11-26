# Cara bikin APK (pilih salah satu)

## Cara A: Android Studio (paling gampang kalau punya laptop)
1. Install Android Studio (gratis): https://developer.android.com/studio
2. Extract zip ini, lalu di Android Studio pilih **Open** dan pilih folder `IslandApp`.
3. Tunggu "Gradle sync" selesai (pertama kali agak lama, butuh internet).
4. Menu **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
5. Klik "locate" di notifikasi kanan bawah. File-nya `app-debug.apk`.
6. Kirim ke HP, install (izinkan "install dari sumber tidak dikenal").

## Cara B: GitHub (tanpa install apa-apa, bisa dari browser)
1. Bikin akun di github.com, lalu buat repository baru (New repository).
2. Upload semua isi folder `IslandApp` (termasuk folder `.github`).
3. Buka tab **Actions**, pilih "Build APK", klik **Run workflow**.
4. Tunggu 3-5 menit sampai centang hijau, buka run-nya, lalu download **Island-APK** di bagian Artifacts.
5. Extract zip-nya, dapat `app-debug.apk`. Install di HP.

## Setelah install
Buka app Island, lalu tekan tombol 1 (izin overlay), tombol 2 (akses notifikasi), dan tombol 3 (nyalakan Island).
