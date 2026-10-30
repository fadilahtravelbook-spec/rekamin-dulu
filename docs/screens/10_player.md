# � Player

**Tujuan:** Putar & kelola satu rekaman.

## Layout (Portrait)

```
┌─────────────────────────────┐
│  ←                      ⋮   │
│                             │
│  ┌───────────────────────┐  │
│  │                       │  │
│  │                       │  │
│  │        ▶              │  │   ← Tap untuk play/pause
│  │                       │  │
│  │                       │  │
│  └───────────────────────┘  │
│                             │
│  RekaminDulu_20250115       │   ← Judul (editable)
│  15 Jan 2025 • 00:45        │   ← Metadata
│  1080p • 24MB               │
│                             │
│  ─────●───────────────────  │   ← Seek bar
│  00:15            00:45     │   ← Current / Total
│                             │
│  ⏪  ⏸  ⏩    🔊    ⛶       │   ← Kontrol: skip, play, speed, volume, fullscreen
│                             │
│  ┌─────────┐  ┌─────────┐   │
│  │ ↗️ Bagarkan│ │ 🗑️ Hapus │   │   ← Tombol aksi bawah
│  └─────────┘  └─────────┘   │
└─────────────────────────────┘
```

## State

| State | Tampilan |
|-------|----------|
| **Loading** | Spinner di tengah video |
| **Playing** | Ikon ⏸ + kontrol auto-hide 3 detik |
| **Paused** | Ikon ▶ besar di tengah |
| **Error** | "Video tidak bisa diputar" + tombol "Buka di aplikasi lain" |

## Detail State

### 1. Loading

- Spinner besar di tengah video.
- Kontrol hidden.
- Teks "Loading..." bawah video.

### 2. Playing

- Video diputar otomatis setelah 500ms.
- Kontrol auto-hide: hilang setelah 3 detik tidak ada aktivitas.
- **Tap video** → toggle kontrol muncul/kembali.
- Ikon pause ⏸ di pojok kiri atas atau tengah bawah.

### 3. Paused

- Video dijeda di frame terakhir.
- Ikon ▶ besar di tengah video.
- User bisa tap untuk melanjutkan.

### 4. Error

- Tampil pesan "Video tidak bisa diputar".
- Tombol "Buka di Aplikasi Lain" → `ACTION_VIEW` dengan URI file.
- Tombol "Coba Lagi" → ulangi pemutaran.

## Kontrol di Bawah

| Ikon | Fungsi |
|------|--------|
| ⏪ | Skip 10 detik mundur |
| ⏸ | Play/Pause (toggle) |
| ⏩ | Skip 10 detik ke depan |
| 🔊 | Volume kontrol + opsi speed (0.5x, 1x, 1.5x, 2x) |
| ⛶ | Fullscreen landscape (rotate otomatis HP) |

## Rotasi Layar (Fullscreen)

- **Portrait → Landscape**: Rotasi otomatis HP, video fill layar.
- **Landscape → Portrait**: Rotasi kembali, video menyesuaikan.
- **Lock orientation**: optional, tapi rekomendasikan auto-rotate untuk player.

## Bagikan

- **ACTION_SEND** dengan `FileProvider` URI.
- Muncul sheet: WhatsApp, Telegram, GDrive, dll.
- **Limit:** file size maksimal 16MB untuk share via WhatsApp (compress otomatis).

## Hapus

- Dialog konfirmasi: "Yakin hapus video ini?".
- Jika ya → hapus file dari storage → tutup player → kembali ke Library.
- **Undo** (optional): Snackbar "Dibatalkan" 3 detik.

## Warna & Typography

| Elemen | Nilai |
|--------|-------|
| Tombol back ← | Warna abu-abu #777, 24sp |
| Judul video | 18sp Regular, warna onSurface |
| Metadata (tanggal/ukuran) | 12sp Regular, opacity 50% |
| Seek bar | Tinggi 4dp, warna primary #D32F2F |
| Teks current/total | 14sp Bold, warna onSurface |
| Tombol aksi (bagikan/hapus) | Height 40dp, radius 8dp |

### Warna Tombol

| Tombol | Warna |
|--------|-------|
| Bagikan | Primary #D32F2F, text putih |
| Hapus | Warning #B00020, text putih |
| Coba Lagi (error) | Primary #D32F2F |

## Dependencies

- `strings.xml`: "player", "buka_di_aplikasi_lain", "coba_lagi", "bagikan", "hapus", "dibatalkan", "skip_10_detik"
- `colors.xml`: primary, warning, surface
- `dimens.xml`: seek bar height, font sizes, padding
- Video player library (ExoPlayer atau similar)
- FileProvider untuk share
- Rotasi otomatis activity

## UX Notes

- **Tap video** → toggle kontrol — ini adalah interaksi utama.
- **Rotate HP** → fullscreen landscape otomatis saat dimutar.
- **Bagikan** → hanya muncul jika file ada dan valid.
- **Hapus** → konfirmasi sebelum hapus permanen.
- Auto-hide kontrol membuat UI lebih bersih saat video diputar.