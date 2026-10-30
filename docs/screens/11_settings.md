# � Settings

**Tujuan:** Konfigurasi rekaman & preferensi pengguna.

## Layout

```
┌─────────────────────────────┐
│  ←  Pengaturan               │
│                             │
│  🎬 Video                   │   ← Section header
│  ┌───────────────────────┐  │
│  │ Resolusi        1080p >│  │
│  ────────────────────────  │
│  │ Frame Rate    30 FPS  >│  │
│  ────────────────────────  │
│  │ Kualitas      Tinggi  >│  │
│  └───────────────────────┘  │
│                             │
│  🔊 Audio                   │
│  ┌───────────────────────┐  │
│  │ Sumber audio    Mic   >│  │
│  └───────────────────────┘  │
│                             │
│  ⚙️ Perilaku                │
│  ┌───────────────────────┐  │
│  │ Countdown          3s >│  │
│  ────────────────────────  │
│  │ Getar saat mulai  [●]  │  │   ← Toggle
│  └───────────────────────┘  │
│                             │
│  🎨 Tampilan                │
│  ┌───────────────────────┐  │
│  │ Tema          Sistem  >│  │
│  └───────────────────────┘  │
│                             │
│  ℹ️ Tentang                 │
│  ┌───────────────────────┐  │
│  │ Versi           1.0.0  │  │
│  ────────────────────────  │
│  │ Kebijakan Privasi    > │  │
│  ────────────────────────  │
│  │ Tentang Aplikasi     > │  │
│  └───────────────────────┘  │
│                             │
└─────────────────────────────┘
```

## Section: Video

| Opsi | Deskripsi | State |
|------|-----------|-------|
| **Resolusi 1080p** | Full HD recording | Radio button / Toggle |
| **Frame Rate 30 FPS** | Lancar, standar | Radio button / Toggle |
| **Kualitas Tinggi** | Bitrate tinggi, file besar | Radio button / Toggle |

### Dialog Pilihan Resolusi

```
┌─────────────────────────────┐
│   Resolusi                  │
│                             │
│   ◉ 720p  (HD)              │   ← Radio button terpilih
│   ○ 1080p (Full HD)         │
│   ○ 1440p (2K)              │
│                             │
│            [Batal] [OK]     │
└─────────────────────────────┘
```

### Detail Setiap Opsi

| Opsi | Resolusi | FPS | File Size (per menit) |
|------|----------|-----|----------------------|
| 720p (HD) | 1280×720 | 30 | ~5MB |
| 1080p (Full HD) | 1920×1080 | 30 | ~8MB |
| 1440p (2K) | 2560×1440 | 30 | ~15MB |

## Section: Audio

| Opsi | Deskripsi | State |
|------|-----------|-------|
| **Sumber audio Mic** | Merekam mikrofon hardware | [●] Actif / [ ] Non-aktif |
| **Sistem Audio** *(belum hadir)* | Audio dari aplikasi | [ ] Disabled + "Segera hadir" |

### Upan Res Audio

- Pilihan "Sistem Audio" sedang dalam pengembangan.
- Jika diaktifkan → butuh izin `CAPTURE_AUDIO_OUTPUT` (Android 13+).
- Saat ini: hanya "Mic" yang aktif, disabled untuk "Sistem" dengan label info.

## Section: Perilaku

| Opsi | Deskripsi | State |
|------|-----------|-------|
| **Countdown 3s** | Hitung mundur sebelum rekam | [3s] / [1s] / [0s] (skip) |
| **Getar saat mulai** | Haptic feedback pada start | [●] On / [ ] Off |
| **Hentikan jika panggilan** | Auto stop saat ada panggilan | [●] On / [ ] Off *(default on)* |

## Section: Tampilan

| Opsi | Deskripsi | State |
|------|-----------|-------|
| **Tema Sistem** | Ikut OS default (Light/Dark) | [●] Sistem |
| **Tema Light** | Force light mode | [ ] Light |
| **Tema Dark** | Force dark mode | [ ] Dark |

### Logika Tema

- **Sistem** → gunakan `AppCompatDelegate.setDefaultNightMode()`.
- **Light** → paksa light mode meskipun OS gelap.
- **Dark** → paksa dark mode meskipun OS terang.

## Section: Tambahan - Mode Gaming

| Opsi | Deskripsi |
|------|-----------|
| **Mode Gaming** | Aktifkan rekaman gameplay dengan fitur lanjutan (Screen Capture, Audio Internal, PiP Camera) |
| **Screen Capture** | [●] On / [ ] Off - Menangkap layar game |
| **Audio Internal** | [ ] On / [ ] Off - Menangkap suara game (Android 10+) |
| **Camera PiP** | [ ] On / [ ] Off - Menampilkan facecam kecil |
| **Indikator Sentuhan** | [●] On / [ ] Off - Menampilkan lingkaran sentuhan |

## State Khusus

| Situasi | Perilaku |
|---------|----------|
| **Rekaman berjalan** | Semua setting **disabled** dengan tooltip: "Tidak bisa diubah saat merekam" |
| **Audio internal** (belum didukung) | Option "Internal" disabled + label "Segera hadir" |

## Warna & Typography

| Elemen | Nilai |
|--------|-------|
| Judul "Pengaturan" | 22sp SemiBold, warna onSurface |
| Section header (Video, Audio, dll) | 16sp SemiBold, warna onSurface |
| Opsi teks | 14sp Regular, warna onSurface |
| Toggle aktif | Primary color #D32F2F |
| Toggle non-aktif | Abu-abu #777, opacity 50% |
| Switch/toggle | Size 20dp, ripple primary |

## Warna Spesifik

| Elemen | Warna |
|--------|-------|
| Section header | #333333 (Light) / #FFFFFF (Dark) |
| Toggle ON | #D32F2F |
| Toggle OFF | #777777 |
| Disabled label | #777777 opacity 50% |
| Tooltip background | #FFF3E0 (light) / #FF8A80 (dark) |

## Dependencies

- `strings.xml`: "pengaturan", "video", "audio", "perilaku", "tampilan", "tentang", "versi", "kebijakan_privasi", "tentang_aplikasi", "countdown_3s", "countdown_1s", "getar_saat_mulai", "hentikan_jika_panggilan", "tema_sistem", "tema_light", "tema_dark", "day", "hapus_data"
- `colors.xml`: primary, surface, onSurface, disabled variant
- `dimens.xml`: height list item 56dp, padding 16dp, switch size
- Logika toggle state penyimpanan di SharedPreferences
- Permission handling untuk audio internal (Android 13+)

## UX Notes

- **Setiap setting memiliki state default** yang konsisten antar platform.
- **Rekaman berjalan** → semua non-wajib di-disable, hanya essential (stop/continue) tetap aktif.
- **Tema** affecting seluruh aplikasi — perlu restart komponen tertentu.
- **Countdown** bisa di-setting 1s, 3s, atau 0s (skip langsung).
- **Haptic** toggle hanya memengaruhi saat mulai rekam, bukan selalu.