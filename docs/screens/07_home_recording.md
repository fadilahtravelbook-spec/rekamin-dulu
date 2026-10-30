# � Home saat Merekam (state khusus)

## Layout

```
┌─────────────────────────────┐
│  📁              ⚙️         │
│                             │
│    Sedang Merekam           │
│    ● 00:15                  │   ← Timer merah berkedip
│                             │
│  ┌───────────────────────┐  │
│  │                       │  │
│  │      🔴 REC           │  │   ← Indikator merekam besar
│  │                       │  │
│  └───────────────────────┘  │
│                             │
│  ┌───────────────────────┐  │
│  │    ⏸    Jeda          │  │   ← Tombol Pause
│  └───────────────────────┘  │
│                             │
│  ┌───────────────────────┐  │
│  │    ⏹    Hentikan      │  │   ← Tombol Stop (merah)
│  └───────────────────────┘  │
└─────────────────────────────┘
```

## Elemen & Interaksi

| Elemen | Aksi |
|--------|------|
| 📁 Library (kiri atas) | Tap → Library (tapi user tetap di recording state) |
| ⚙️ Settings (kanan atas) | Tap → Settings (tapi disabled dengan tooltip) |
| Timer "● 00:15" | Merah berkedip, menunjukkan rekaman berjalan |
| 🔴 REC (lokal) | Indikator besar di tengah — warna primary |
| Tombol Jeda (⏸) | Pause/resume rekaman |
| Tombol Stop (⏹) | Hentikan rekaman → simpan → transisi ke Library |

## State Transitions

| Dari State | Ke State | Aksi |
|------------|----------|------|
| Home Default | Home saat Merekam | User tap FAB → Countdown → REC |
| Home saat Merekam | Library | User tap Stop → Save → Transisi |
| Home saat Merekam | Home Default | User tutup notifikasi / app diblokir (tidak mungkin) |

## UX Notes

- User **bisa meninggalkan Home** → kontrol utama via notifikasi persistens.
- Jika user buka **Home lagi** → tampil state ini (bukan Home default dengan FAB rekam).
- **Stop** → langsung stop, simpan, transisi ke Library (tidak ada dialog konfirmasi "yakin stop?").
- Timer "● 00:15" → merah berkedip (blink pattern: 1s on, 1s off).
- Semua tombol memiliki **haptic feedback** saat ditekan.

## Warna & Typography

| Elemen | Nilai |
|--------|-------|
| Teks "Sedang Merekam" | 20sp SemiBold, warna primary #D32F2F |
| Timer "● 00:15" | 36sp Bold, warna merah #D32F2F, berkedip |
| Teks "REC" | 24sp Bold, warna onPrimary |
| Tombol Jeda | Abu-abu #777, text putih |
| Tombol Stop | Merah #B00020, text putih, radius 28dp |
| Ikon library/settings | Abu-abu, opacity 60% jika disabled |

## State Khusus

| Situasi | Tampilan |
|---------|----------|
| **Default** | Seperti di atas (tidak ada rekaman) |
| **Mikrofon OFF** | Timer abu-abu, teks "Mikrofon mati" |
| **Rekam 1+ menit** | Timer berubah, warna tetap merah |
| **Sedang Paused** | Tombol jadi ▶ Lanjut, stop masih ada |

## Dependencies

- `colors.xml`: primary (#D32F2F), warning (#B00020) tombol stop
- `dimens.xml`: timer font 36dp, tombol height 48dp, radius 8dp
- `strings.xml`: "sedang_merekam", "jeda", "hentikan", "timer_rekam"
- Logika timer: countdown dari countdown screen + accumulate waktu
- Haptic feedback pada setiap tap tombol