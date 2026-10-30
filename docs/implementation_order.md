# 📋 Urutan Implementasi UI

Berikut rekomendasi urutan pengerjaan layar berdasarkan prioritas dan dependensi.

---

## Prioritas P0 (Foundation — Harus Selesai Pertama)

| # | Halasan | Deskripsi | Estimasi | Dependensi |
|---|---------|-----------|----------|------------|
| P0-1 | **Home** | Halaman utama, tombol rekam, toggle mikrofon, info resolusi | 1 hari | Tema, Warna, String |
| P0-2 | **Dialog Izin** | Minta RECORD_AUDIO, POST_NOTIFICATIONS, MediaProjection | 1 hari | Home |
| P0-3 | **Countdown** | Layar hitung mundur 3→2→1 sebelum rekam | 1 hari | Dialog Izin |
| P0-4 | **Notifikasi Recording** | Notifikasi persisten dengan timer, tombol Jeda/Stop | 1 hari | Countdown, Service |

## Prioritas P1 (Core Features)

| # | Halasan | Deskripsi | Estimasi | Dependensi |
|---|---------|-----------|----------|------------|
| P1-1 | **Library** | Daftar rekaman, empty state, search, menu bottom sheet | 1 hari | Notifikasi Recording |
| P1-2 | **Player** | Putar video, seek bar, kontrol play/pause, bagikan, hapus | 1 hari | Library |

## Prioritas P2 (Pengaturan & Branding)

| # | Halasan | Deskripsi | Estimasi | Dependensi |
|---|---------|-----------|----------|------------|
| P2-1 | **Settings** | Resolusi, FPS, kualitas, sumber audio, countdown, getar, tema | 1 hari | Home |
| P2-2 | **Splash** | Branding 1 detik, cek onboarding status | 2 jam | Onboarding |

## Prioritas P3 (Polish & Tambahan)

| # | Halasan | Deskripsi | Estimasi | Dependensi |
|---|---------|-----------|----------|------------|
| P3-1 | **Onboarding** | 3 slide edukasi, skip, dot indicator | 2 jam | Home |
| P3-2 | **Polish** | Animasi, haptic feedback, edge cases, toast/snackbar | 1 hari | Semua layar |

---

## Total Estimasi

- **UI saja: ~6–7 hari kerja**
- **Belum termasuk:** Logic service rekaman, MediaProjection, FileProvider, penyimpanan file, dll.

---

## Catatan Penting

1. Selesaikan **P0-1 hingga P0-4** sebelum memulai P1 karena fitur inti bergantung pada notifikasi dan izin.
2. **Home** harus menangani state "rekam berjalan" agar user tetap bisa kontrol meski belum ada Player.
3. **Settings** harus disabled saat rekaman berjalan (tooltip: "Tidak bisa diubah saat merekam").
4. Semua layar harus memiliki **3 state minimal**: default, loading, empty/error.