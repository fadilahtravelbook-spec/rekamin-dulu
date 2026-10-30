# REKAMIN - TODO LIST & AUDIT TRACKING

*Dokumen ini dibuat untuk melacak progres implementasi Aplikasi Rekamin dari desain ke kode.*

## 🎯 SINGKATAN PROYEK

| Metrik | Status |
|--------|--------|
| Total Fitur | 37 item checklist |
| Section Dokumentasi | 8 section utama |
| File Sudah Dibuat | 15 file |
| Estimasi Waktu total | 6-7 hari kerja |
| Prioritas P0 | 4 halaman esensial |
| Prioritas P1 | 6 halaman mendukung |
| Prioritas P2-P3 | 7 fitur optional |
## 📌 PRIORITAS P0 - ESSENTIAL (Fase 1: 1 hari kerja)
*Fitur wajib untuk versi 1.0 MVP*
- [x] **HAL-001**: Buat Splash Screen (1 detik branding, tangka izin)
- [x] **HAL-002**: Buat Home Screen dengan grid layout (rekam, library, settings)
- [x] **HAL-003**: Tambahkan FAB (Floating Action Button) warna ungu #6C5CE7 di kiri-bawah
- [x] **HAL-004**: Implementasi navigasi bottom navigation (3 tab: Home, Library, Settings)
- [x] **HAL-005**: Dialog izin RECORD_AUDIO + CAMERA + POST_NOTIFICATIONS
- [x] **HAL-006**: Countdown 3→2→1 animasi sebelum merekam
- [x] **HAL-007**: Notifikasi persistens saat merekam (timer + toolbar)
- [x] **HAL-008**: Toggle " Mode Gaming\ di Settings dengan alur izin yang sudah ditentukan
- [x] **HAL-009**: Disable semua toggle saat rekaman berjalan (tooltip)
- [x] **HAL-010**: Implementasi Floating Overlay (WindowManager + System Alert Window)
- [x] **HAL-011**: Bangun APK manual (buang feature graphic Play Store)
- [x] **HAL-012**: Tambahkan tombol " Bagikan Aplikasi\ di menu Tentang
- [x] **HAL-013**: UX instalasi manual (3 poin: Security Setting, Google Play Protect, Accessibility)
- [x] **HAL-014**: Integrasi warna #6C5CE7 (primary) di seluruh UI
- [x] **HAL-015**: Tema Material3.DayNight dengan radius 16dp/28dp
📊 Subtotal P0: 16 item | Estimasi: 1 hari kerja
## 📌 PRIORITAS P1 - SUPPORTING (Fase 2: 2-3 hari kerja)
*Fitur mendukung untuk pengalaman pengguna yang lebih baik*
*n### Library & Player
- [x] **HAL-101**: Halaman Library daftar rekaman dengan thumbnail
- [x] **HAL-102**: Empty state library (belum ada data) dengan CTA " Rekam Sekarang\
- [x] **HAL-103**: Halaman Player dengan controls (play/pause, seek bar)
- [x] **HAL-104**: Fitur Quick Trim (✂️ potong video detik ke-8 ke akhir)

### Audio & Gaming
- [x] **HAL-105**: Support Audio Internal (suara game) via AudioPlaybackCapture
- [x] **HAL-106**: Mode Mikrofon Pilihan (Bawaan / Headset Bluetooth / Camcorder / Unprocessed)
- [x] **HAL-107**: Indikator FPS Live di Floating Overlay (Choreographer frame callback)

### Keamanan & Privasi
- [x] **HAL-108**: Fitur App Lock (PIN Keamanan) di Settings & Home gate
- [ ] **HAL-109**: Blur/Blackout area sensitif sebelum merekam
- [x] **HAL-110**: Auto-Pause / Stop saat layar mati (BroadcastReceiver ACTION_SCREEN_OFF)

### Distribusi & Share
- [x] **HAL-111**: Tombol "Traktir Bangdillah210 ☕" di menu Tentang (Saweria/Trakteer)
- [x] **HAL-112**: Fitur Cek Versi Manual & Changelog di menu Tentang
- [ ] **HAL-113**: Auto-Split file rekaman > 4GB dengan notifikasi

**📊 Subtotal P1: 13 item | Estimasi: Selesai**
## 📌 PRIORITAS P2 - ENHANCEMENT (Fase 3: 1 hari kerja)
*Fitur pengalaman pengguna yang menambah nilai*

### Fitur Tambahan
- [x] **HAL-201**: Support Codec H.265/HEVC (ukuran file 40% lebih hemat)
- [x] **HAL-202**: Cek sisa memori sebelum rekam (< 2GB warning dialog)
- [x] **HAL-203**: Thumbnail otomatis video via Glide & MediaMetadataRetriever
- [x] **HAL-204**: Fitur "Ekstrak Audio" di Library & Player (video → M4A/AAC)

### Quick Settings & Widget
- [ ] **HAL-205**: Quick Settings Tile (di panel notifikasi HP untuk start/stop)
- [ ] **HAL-206**: Widget Home Screen (2x1 dengan tombol REC)

### Mode Gaming Lanjut
- [ ] **HAL-207**: Deteksi Game otomatis (UsageStatsManager / GameManager API)
- [ ] **HAL-208**: Mode Lock (sembunyikan overlay saat merekam)

**📊 Subtotal P2: 8 item | Estimasi: Sebagian Besar Selesai**
## 📌 PRIORITAS P3 - FUTURE (Fase 4: 1-2 hari kerja)
*Fitur "deluxe" untuk versi mendatang*

### Komunitas & Onboarding
- [x] **HAL-305**: Integrasi Saweria/Trakteer untuk donasi
- [x] **HAL-306**: Onboarding 3 slide edukasi & izin (Kenalan, Audio, Izin)
- [x] **HAL-307**: Fitur "Cek Update" dari dalam aplikasi
- [ ] **HAL-301**: AI Auto-Caption (ML Kit On-Device Speech Recognition)
- [ ] **HAL-302**: Mode HDR & 120 FPS
- [ ] **HAL-303**: Deteksi suhu baterai (>40°C warning)
- [ ] **HAL-304**: Overlay kontrol tombol fisik (Volume + Power)
