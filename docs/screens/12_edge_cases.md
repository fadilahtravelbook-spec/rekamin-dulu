# � Edge Cases & Micro-UX

## 1. User Mencabut Izin Saat Merekam
- Notifikasi hilang, service stop, file tersimpan. Toast: "Rekaman dihentikan. Tersimpan."

## 2. Storage Penuh
- Rekaman stop + error: "Penyimpanan penuh". Dialog opsi "Bersihkan dan Coba Lagi".

## 3. HP Mati/Restart Saat Merekam
- Service mati, file mungkin rusak. Tidak ada recovery otomatis.
- Saat buka lagi: notifikasi "Rekaman terakhir mungkin tidak selesai".

## 4. Rotasi Layar Saat Rekam
- Rekaman tidak ikut rotate (tetap resolusi awal).
- Di Home: teks kecil "Rotasi layar tidak mempengaruhi rekaman".

## 5. Panggilan Masuk Saat Merekam
- MediaRecorder tetap jalan. Audio mungkin hilang/clash.
- Rekaman tetap jalan, tapi kualitas audio bisa menurun.

## 6. Low Battery During Recording
- < 15%: Notifikasi warning + opsi Lanjut/Stop.
- Auto-stop jika battery habis total.

## 7. Rapid Tap FAB
- Debounce 500ms antara tap.
- Haptic feedback konfirmasi.

## 8. Recent App Switch
- State rekaman masih ada di notifikasi setelah kembali.
- Bisa resume dari notifikasi.

## 9. Landscape Mode di Home
- UI tetap portrait, tidak ikut rotasi.
- Optimized untuk single-activity Material 3.

## 10. UX Saat Install APK Manual (Baris baru)
*Karena aplikasi didistribusikan melalui file APK manual (bukan Play Store), pengguna akan menemui layar peringatan Android standar. Catatan ini penting untuk dipahami pengguna pertama kali:*

**1. Izin "Instal Aplikasi Tidak Dikenal":**
- Saat pertama kali menginstal dari WhatsApp/Telegram/File Manager, HP akan menampilkan pesan: "Menginstall aplikasi dari sumber tidak dikenal".
- **Solusi:** Pergi ke *Setting* → *Security* (atau *Privacy*) → aktifikan opsi "Instal aplikasi dari sumber ini" (untuk File Manager/WhatsApp).

**2. Peringatan Google Play Protect:**
- Akan muncul pop-up: "Aplikasi ini tidak diuji keamanan Play Protect".
- **Solusi:** Tekan **"Detail Lainnya" (More Details)** → lalu pilih **"Tetap Instal" (Install Anyway)**.

**3. Izin Aksesibilitas & Mikrofon:**
- Saat aplikasi pertama kali dibuka, akan muncul permintaan izin.
- Izin yang dibutuhkan: **Mikrofon** (untuk merekam suara) dan **Notifikasi** (untuk notifikasi rekaman).
- **Tips:** Izinkan semua izin agar fitur Rekamin bekerja sempurna.

## 11. Edge Cases Mode Gaming (Baris baru)
*Kasus-kasus khusus saat menggunakan Mode Gaming pada Rekamin:*

**A. Aplikasi Game dengan FLAG_SECURE**
- Beberapa game melindungi layar mereka menggunakan `FLAG_SECURE`, yang mencegah `MediaProjection` menangkap konten.
- **Penanganan:** Deteksi otomatis saat mencoba mulai rekaman. Jika terdeteksi, nonaktifkan overlay dan tampilkan pesan: "Game ini melindungi layar. Coba keluar dari game atau nonaktifkan mode proteksi".

**B. `SecurityException` saat `startForeground`**
- Terjika Service tidak memiliki izin yang tepat atau kombinasi tipe `FOREGROUND_SERVICE_TYPE_*` tidak sesuai.
- **Penanganan:** Cek manifest dan deklarasi izin. Tampilkan pesan: "Izin sistem diperlukan untuk memulai rekaman". Aplikasi harus berhenti secara terkontrolled.

**C. Token `MediaProjection` Dicabut Saat Rekam**
- Pengguna menutup izin tangkap layar di setting saat sedang merekam.
- **Penanganan:** Daftarkan `MediaProjection.Callback` dan hentikan semua *pipeline* (encoder, muxer, overlay) saat `onStop()` dipanggil. Simpan file yang sudah terbuat dengan pesan: "Rekaman dihentikan karena izin dicabut".

**D. Audio Internal Tidak Terdeteksi**
- Game tidak mengizinkan penangkapan audio (`ALLOW_CAPTURE_BY_NONE`).
- **Penanganan:** Nonaktifkan opsi Audio Internal secara otomatis. Tampilkan tooltip: "Game ini tidak mengizinkan rekaman audio internal. Aktifkan Mikrofon saja jika diperlukan".

**E. Overlay Menutupi Tombol Kontrol Game**
- Floating Overlay bisa menutupi tombol kontrol di game, menyebabkan game tidak bisa dimainkan.
- **Penanganan:** Saat mode "Lock" aktif, nonaktifkan overlay sepenuhnya (alpha 0) atau geser overlay ke sudut layar. Beri opsi "Sembunyikan Overlay" di Quick Settings.

**F. Baterai Habis Saat Rekam**
- Mode gaming berat membebani CPU/GPU, sehingga baterai habis lebih cepat.
- **Penanganan:** Beri peringatan saat baterai < 20% saat mode gaming aktif: "Baterai rendah. Rekaman akan dihentikan otomatis dalam 5 menit untuk menyimpan file". Atau berikan opsi "Lanjutkan meski baterai rendah".

---

---