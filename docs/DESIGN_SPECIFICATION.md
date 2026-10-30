# 🎨 Desain Dokumen — "Rekamin Dulu by Bangdillah210"

Aplikasi rekaman layar Android dengan desain **Material 3 (Material You)**, warna utama merah (#D32F2F), dan dukungan tema sistem/gelap/terang.

---

## 1. Prinsip Desain

| Aspek | Keputusan |
|-------|-----------|
| **Style** | Material 3 (Material You) |
| **Warna utama** | Merah `#D32F2F` — identik tombol "rekam" |
| **Warna sekunder** | Abu gelap `#1E1E1E` untuk tema gelap |
| **Font** | Roboto / Inter |
| **Ikon** | Material Symbols |
| **Sudut** | Rounded 16dp (kartu), 28dp (FAB/tombol besar) |
| **Spasi** | Grid 8dp |
| **Tema** | System / Light / Dark (ikut OS default) |

---

## 2. Peta Alur Utama

```
[Splash] → [Home] → [Permission Dialog] → [Countdown] → [REC] → [Library] → [Player]
              ↕                                                        ↓
          [Settings]                                                [Hapus/Bagikan]
```

---

## 3. Design Tokens (Ringkasan untuk Developer)

| Token | Value | Keterangan |
|-------|-------|------------|
| `colorPrimary` | `#D32F2F` | Warna utama (merah) |
| `colorOnPrimary` | `#FFFFFF` | Teks/ikon di atas warna utama |
| `colorSurface` | `#FAFAFA` (Light) / `#121212` (Dark) | Latar permukaan |
| `colorError` | `#B00020` | Warna error/hapus |
| `textHeading` | 28sp Bold | Judul besar |
| `textTitle` | 22sp SemiBold | Judul layar |
| `textBody` | 16sp Regular | Teks isi |
| `textCaption` | 14sp Regular opacity 60% | Keterangan kecil |
| `spacing` | 8dp × n | Grid spasi |
| `radiusCard` | 16dp | Sudut kartu |
| `radiusButton` | 28dp | Sudut tombol besar |
| `elevation` | 0dp | Flat M3 |

---

## 4. Daftar Layar & State

| # | Layar | State Minimal |
|---|-------|---------------|
| 1 | Splash | Default |
| 2 | Onboarding (3 slide) | Default, Skip |
| 3 | Home | Default, Mikrofon ON, Izin ditolak, Rekam berjalan, Loading |
| 4 | Dialog Izin | Rationale, Dialog sistem, Dialog kustom (ditolak) |
| 5 | Countdown | 3→2→1, Batalkan, Skip (0 detik) |
| 6 | Recording State | Recording, Paused, Saving (hanya notifikasi) |
| 7 | Home saat Rekam | Timer berkedul, Tombol Jeda, Tombol Stop |
| 8 | Simpan (Toast) | Berhasil, Gagal |
| 9 | Library | Empty, Loading, Ada data, Error |
| 10 | Player | Loading, Playing, Paused, Error |
| 11 | Settings | Default, Rekam berjalan (semua disabled) |

---

## 5. Edge Cases & Micro-UX

1. **Izin dicabut saat rekam** → Notifikasi hilang, service stop, file tersimpan. Toast: "Rekaman dihentikan. Tersimpan."
2. **Storage penuh** → Rekaman otomatis stop + dialog error.
3. **HP mati/restart saat rekam** → File mungkin rusak. Notifikasi saat buka ulang: "Rekaman terakhir mungkin tidak selesai."
4. **Rotasi layar saat rekam** → Rekaman tidak ikut rotate. Teks kecil di Home: "Rotasi layar tidak mempengaruhi rekaman."
5. **Panggilan masuk saat rekam** → MediaRecorder tetap jalan, audio mungkin hilang.

---

## 6. Urutan Implementasi (Rekomendasi)

| Prioritas | Halaman | Estimasi |
|-----------|---------|----------|
| P0 | Home + Tombol Rekam | 1 hari |
| P0 | Dialog Izin + Countdown | 1 hari |
| P0 | Notifikasi (Recording state) | 1 hari |
| P1 | Library (list + empty state) | 1 hari |
| P1 | Player | 1 hari |
| P2 | Settings | 1 hari |
| P2 | Splash | 2 jam |
| P3 | Onboarding | 2 jam |
| P3 | Polish (animasi, haptic, edge case) | 1 hari |

**Total UI saja: ~6–7 hari** (belum termasuk logic service).

---

## 7. File Pendukung

| File | Path | Deskripsi |
|------|------|-----------|
| Warna | `app/src/main/res/values/colors.xml` | Palet Material 3 Light + Dark |
| Teks | `app/src/main/res/values/strings.xml` | Semua string UI (Bahasa Indonesia) |
| Dimensi | `app/src/main/res/values/dimens.xml` | Spacing, radius, typography |
| Tema | `app/src/main/res/values/themes.xml` | Tema System/Light/Dark |

---

## 8. Penyesuaian untuk Aplikasi Non-Play Store (APK Mandiri)

*Dokumen ini dibuat untuk kasus penggunaan aplikasi yang didistribusikan melalui file APK manual (sideloading), bukan melalui Google Play Store. Penyesuaian ini berlaku untuk versi 1.0.0 ke atas.*

### 8.1 Buang Aset Play Store
- **Tidak perlu membuat** *Feature Graphic* (Banner 1024x500).
- **Tidak perlu membuat** *Screenshot* khusus untuk Play Store. Cukup gunakan *Screenshot* asli dari HP untuk dipromosikan ke teman-teman via WhatsApp atau Media Sosial.
- **Aset yang tetap wajib ada:** App Icon (Ikon Aplikasi) yang Anda berikan (karakter Chibi). Buat dalam format PNG ukuran **512x512 px**. Nanti sistem Android akan otomatis membentuknya menjadi bulat/kotak di layar utama HP.

### 8.2 Penyesuaian UI di Halaman "Tentang Aplikasi"
Karena tidak ada Play Store yang otomatis mengurus *update*, tambahkan fitur kecil di halaman **Settings > Tentang**:
- **Cek Versi:** Tulis versi dengan jelas (contoh: "Versi 1.0.0").
- **Tombol "Bagikan Aplikasi" (Share APK):** Tambahkan satu tombol di menu Tentang. Jika ditekan, akan membuka menu *Share* Android untuk mengirim file `.apk` Rekamin ke teman via WhatsApp/Telegram. (Sangat berguna jika Anda ingin teman Anda juga memakainya).
- **Kredit Pengembang:** Pastikan tulisan "Dibuat oleh Bangdillah210" menonjol, sebagai tanda terima kasih pada diri sendiri dan branding.

### 8.3 UX Saat Pengguna Menginstal APK (Penting!)
Karena pengguna akan menginstal dari luar Play Store, mereka akan disambut layar peringatan dari Android. Anda perlu mengedukasi mereka (lewat pesan teks atau video singkat saat membagikan file) tentang:
1.  **Izin "Instal Aplikasi Tidak Dikenal":** Pengguna harus mengizinkan *File Manager* atau *WhatsApp* untuk menginstal aplikasi.
2.  **Peringatan Google Play Protect:** Saat menginstal, akan muncul peringatan "Aplikasi Tidak Aman". Instruksi: Tekan **"Detail Lainnya" (More Details)** lalu pilih **"Tetap Instal" (Install Anyway)**.
3.  **Izin Aksesibilitas:** Pastikan saat pertama kali dibuka, aplikasi meminta izin mikrofon dan notifikasi dengan sopan (seperti yang sudah dirancang di UI).

### 8.4 Keuntungan & Kerugian (Untuk Anda Ketahui)
- **Keuntungan:** 100% Gratis, bebas iklan, ukuran file APK sangat kecil (bisa di bawah 10 MB), dan tidak ada batasan dari Google.
- **Kekurangan:** Pengguna harus menginstal manual. Jika Anda memperbarui aplikasi (misal dari v1.0 ke v1.1), pengguna harus mengunduh file APK baru dan menginstalnya ulang (data pengaturan biasanya aman, tapi lebih aman jika Anda memberi tahu mereka).

### 8.5 Keamanan File APK (Wajib Diperhatikan Developer)
- Pastikan saat melakukan *Build APK* di Android Studio, Anda menggunakan **Release Mode** (bukan Debug), lalu *Sign* APK tersebut dengan *Keystore* Anda sendiri.
- Jika tidak di-*sign*, beberapa HP mungkin akan menolak menginstalnya.
- Jangan lupa mencentang opsi **V1 dan V2 (Full APK Signature)** saat melakukan *Generate Signed Bundle / APK* di Android Studio, agar kompatibel dengan semua versi Android (lama dan baru).

### 8.6 Distribusi ke Komunitas/Pribadi
- File APK hasil build dapat dibagikan melalui WhatsApp, Telegram, atau langsung dipindahkan ke perangkat lain via USB/Flashdisk.
- Tidak ada batasan jumlah unduhan atau pengguna, karena tidak ada Play Store yang mengaturnya.
- Saran: Sertakan file *README* sederhana beserta file APK yang menjelaskan cara instalasi dan izin yang dibutuhkan.

## 9. Mode Gaming (Baris baru - Bagian 1)
*Fitur ini merupakan ekstensif dari desain utama Rekamin, memungkinkan pengguna merekam gameplay dengan kualitas profesional.*

### 9.1 Arsitektur Sistem & Komponen Inti
| Komponen | Teknologi | Fungsi Utama |
| :--- | :--- | :--- |
| **Foreground Service** | `Service` dengan tipe `mediaProjection`, `camera`, `microphone` | Menjaga proses rekaman tetap hidup di latar belakang saat game berjalan. |
| **Screen Capture** | `MediaProjection` + `VirtualDisplay` | Menangkap konten visual dari layar game. |
| **Video Encoding** | `MediaCodec` (Surface Input) | Meng-encode frame dari `VirtualDisplay` menjadi format video (H.264/H.265). |
| **Audio Capture** | `AudioRecord` + `AudioPlaybackCaptureConfiguration` | Menangkap audio internal dari game (Android 10+). |

### 9.2 Urutan Izin yang Kritis (Android 14+)
1.  **Izin Aplikasi (Runtime Permissions):** Minta izin `RECORD_AUDIO`, `CAMERA`, dan `POST_NOTIFICATIONS`.
2.  **Izin Overlay:** Minta izin `SYSTEM_ALERT_WINDOW` melalui `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`.
3.  **Izin Tangkap Layar:** Panggil `MediaProjectionManager.createScreenCaptureIntent()` untuk mendapatkan token persetujuan dari pengguna.
4.  **Mulai Foreground Service (WAJIB):** **Sebelum** memproses token `MediaProjection`, jalankan `startForegroundService()` dengan `ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` (dan `CAMERA`/`MICROPHONE` jika diperlukan).
5.  **Mulai Rekaman:** Baru setelah *service* berjalan, aplikasi bisa memanggil `getMediaProjection()` menggunakan token yang didapat dan memulai *pipeline* rekaman.

### 9.3 Alur Spesifik per Fitur

**A. Audio Internal (Suara Game)**
*   **Syarat:** Android 10 (API 29) ke atas.
*   **Alur Teknis:**
    1.  Buat `AudioRecord` dengan `AudioPlaybackCaptureConfiguration`.
    2.  Tambahkan filter `addMatchingUsage(AudioAttributes.USAGE_GAME)` atau `USAGE_MEDIA`.
    3.  **Catatan:** Jika game mengatur `AudioAttributes.ALLOW_CAPTURE_BY_NONE`, aplikasi Anda tidak akan bisa merekam suaranya.

**B. Tombol Melayang (Floating Overlay)**
*   **Teknis:** Gunakan `WindowManager.addView()` dengan `LayoutParams` bertipe `TYPE_APPLICATION_OVERLAY`.
*   **Alur UX:** Ikon kecil yang bisa di-*drag*. Saat di-*tap*, membesar dan menampilkan tiga tombol: **Pause/Resume**, **Stop**, **Sembunyikan**.
*   **Manajemen:** Overlay harus dihapus (`removeView`) saat rekaman berhenti.

**C. Kamera PiP (Facecam)**
*   **Tantangan:** `CompositionSettings` pada `CameraX` bersifat *bind-time*. Jika ingin mengganti posisi kamera saat merekam, *session* harus di-*rebind* dan ini akan mematikan encoder video.
*   **Solusi Pro (OpenGL):**
    1.  Gunakan dua `SurfaceTexture`: satu untuk *output* `MediaProjection`, satu lagi untuk *preview* `CameraX`.
    2.  Gabungkan keduanya dalam satu *fragment shader* di *render thread* menggunakan OpenGL ES (GLES).
    3.  Hasil gabungan dikirim ke `Surface` milik `MediaCodec` untuk di-encode menjadi video final.

**D. Indikator Sentuhan (Touch Highlights)**
*   **Pendekatan Sistem (Paling Mudah):** Arahkan pengguna untuk mengaktifkan fitur "Show taps" (Tampilkan sentuhan) bawaan Android di menu **Developer Options**.
*   **Pendekatan Aplikasi (Kustom):** Aplikasi harus membuat overlay transparan berukuran penuh (`TYPE_APPLICATION_OVERLAY`) yang menangkap *event* sentuhan dan menggambar lingkaran animasi memudar di titik koordinat sentuhan.

| File | Path | Deskripsi |
|------|------|-----------|
| Warna | `app/src/main/res/values/colors.xml` | Palet Material 3 Light + Dark |
| Teks | `app/src/main/res/values/strings.xml` | Semua string UI (Bahasa Indonesia) |
| Dimensi | `app/src/main/res/values/dimens.xml` | Spacing, radius, typography |
| Tema | `app/src/main/res/values/themes.xml` | Tema System/Light/Dark |