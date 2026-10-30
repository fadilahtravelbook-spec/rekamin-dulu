# � Home (Halaman Utama)

**Tujuan:** Titik start rekaman & akses cepat ke library & setting.

## Layout

```
┌─────────────────────────────┐
│  📁              ⚙️         │   ← Library (kiri), Settings (kanan)
│                             │
│    Rekamin Dulu             │   ← Judul 22sp bold
│    Siap merekam             │   ← Status 14sp, opacity 60%
│                             │
│  ┌───────────────────────┐  │
│  │                       │  │
│  │                       │  │   ← Preview area (kotak abu-abu besar)
│  │    🎥 Siap merekam    │  │      menampilkan ilustrasi/placeholder
│  │                       │  │
│  └───────────────────────┘  │
│                             │
│  ┌─────────────────────┐    │
│  │  🎤 Mikrofon    [●]  │    │   ← Toggle audio
│  └─────────────────────┘    │
│                             │
│  ┌─────────────────────┐    │
│  │  📐 1080p • 30 FPS   │    │   ← Info pengaturan (tap → Settings)
│  └─────────────────────┘    │
│                             │
│                             │
│    ╔═══════════════════╗    │
│    ║   🔴  MULAI REKAM  ║    │   ← FAB besar full-width
│    ╚═══════════════════╝    │
│                             │
└─────────────────────────────┘
```

## Elemen & Interaksi

| Elemen | Aksi / State |
|--------|--------------|
| 📁 Library (kiri atas) | Tap → Library |
| ⚙️ Settings (kanan atas) | Tap → Settings |
| Preview area | Placeholder ilustrasi; jika ada rekaman terakhir → thumbnail + play |
| Toggle Mikrofon | Ubah Prefs + cek izin |
| Info resolusi (1080p • 30 FPS) | Tap → Settings |
| Tombol "MULAI REKAM" (FAB) | Pemicu alur utama: Home → Countdown → REC |

## State

| State | Tampilan | Perilaku |
|-------|----------|----------|
| **Default** | Seperti di atas | Mic OFF, tidak ada rekaman berjalan |
| **Mikrofon ON** | Toggle berwarna, teks berubah | Prefs diupdate, cek permission |
| **Izin ditolak** | Banner kuning "Aktifkan izin untuk merekam" + tombol "Buka Pengaturan" | FAB disabled atau abu-abu |
| **Rekaman berjalan** | Tombol berubah → "🔴 SEDANG MEREKAM — Tap untuk lihat" | Pemicu Countdown |
| **Loading** | Tombol disabled + spinner | Saat inisialisasi service |

### Interaksi Khusus

- **Haptic feedback** saat tap tombol rekam (kecil, 10ms vibration).
- Ikon Library menampilkan **badge count** jumlah rekaman (misal: "📁 5").
- **Snackbar** jika mikrofon ON tapi izin belum ada: "Izin mikrofon dibutuhkan".
- **Empty state** kalau belum ada izin sama sekali → tombol FAB disabled + banner info.

## Warna & Typography

| Elemen | Nilai |
|--------|-------|
| Judul "Rekamin Dulu" | 22sp SemiBold, warna onSurface |
| Status "Siap merekam" | 14sp Regular, opacity 60% |
| Toggle "Mikrofon" | 16sp Regular |
| Info "1080p • 30 FPS" | 14sp Regular, opacity 60% |
| FAB "MULAI REKAM" | Warna primary (#D32F2F), text putih, radius 28dp |

## Warna Spesifik State

| State | Warna |
|-------|-------|
| Mic OFF | Abu-abu #777 |
| Mic ON | Merah #D32F2F |
| Izin ditolak | Kuning #F57F17 (banner) |
| FAB default | #D32F2F |
| FAB disabled | #CCCCCC, opacity 50% |

## Dependencies

- `colors.xml`: primary, onPrimary, surface, onSurface
- `dimens.xml`: FAB diameter 56dp, radius 28dp, padding 16dp/24dp
- `strings.xml`: "library", "settings", "mic_toggle", "resolution_info", "mulai_rekam"
- `themes.xml`: FAB elevation 4dp (pressed state)