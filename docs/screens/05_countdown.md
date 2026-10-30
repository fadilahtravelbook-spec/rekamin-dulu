# � Countdown Screen

**Tujuan:** Beri waktu persiapan sebelum rekaman dimulai.

## Layout

```
┌─────────────────────────────┐
│                             │
│                             │
│                             │
│           ⭕                │
│         ╱     ╲             │
│        │   3   │            │   ← Lingkaran besar dengan angka
│         ╲     ╱             │      berdenyut
│           ⭕                │
│                             │
│     Bersiap merekam...      │
│                             │
│                             │
│    ┌──────────────────┐     │
│    │     Batalkan     │     │
│    └──────────────────┘     │
└─────────────────────────────┘
```

## Behavior

| Fitur | Deskripsi |
|-------|-----------|
| **Animasi angka** | 3 → 2 → 1 dengan animasi **scale-in** (300ms) + fade-out |
| **Ring progress** | Berputar searah jarum jam |
| **Haptic** | Vibrate setiap angka berganti |
| **Skip dari Settings** | Countdown = 0 detik, langsung ke RECORDING |
| **Tombol Batalkan** | Batal rekaman sebelum mulai (belum ada file) |

## Detail Animasi

| Langkah | Durasi | Animasi |
|---------|--------|---------|
| Angka 3 tampil | 0ms | — |
| Transisi ke 2 | 300ms | Scale-in + fade-out 3, scale-in 2 |
| Transisi ke 1 | 300ms | Scale-in + fade-out 2, scale-in 1 |
| Fade-out angka 1 | 200ms | — |
| Total | ~1s | Langsung ke RECORDING setelah 1 |

## Ring Progress

- Berputar searah jarum jam sepenuhnya (360°) selama countdown berlangsung.
- Warna: primary (#D32F2F) dengan stroke 8dp.
- Ketika angka berubah, ring sedikit "reset" animasi rotasi.

## Tombol Batalkan

- Berwarna abu-abu, text abu-abu.
- Tap → dismiss countdown → kembali ke Home state (mic bisa tetap ON atau OFF).
- **Catatan:** Batal berarti **belum ada file rekaman** yang dibuat.

## Warna & Typography

| Elemen | Nilai |
|--------|-------|
| Lingkaran/angka | Merah #D32F2F |
| Teks "3", "2", "1" | Putih, 48sp Bold |
| Teks "Bersiap merekam..." | 18sp Regular, opacity 80% |
| Tombol "Batalkan" | Abu-abu #777, text putih, tinggi 48dp, radius 8dp |
| Latar | Transparan / surface |

## Warna State

| State | Warna |
|-------|-------|
| Angka aktif | #D32F2F |
| Ring progress | #D32F2F stroke |
| Teks "Bersiap..." | #777777 opacity 80% |
| Tombol Batal (hover) | #F57F17 |

## Dependencies

- `dimens.xml`: circle diameter 200dp, ring stroke 8dp, font 48sp
- `colors.xml`: primary untuk ring dan angka
- `animations.xml`: scale_in_300ms, fade_out_200ms, ring_rotation
- `strings.xml`: "bersiap_merekam", "batalkan"

## UX Notes

- **Bisa di-skip** dari Settings (countdown = 0).
- Jika user tidak melakukan apa-apa → otomatis melanjutkan ke RECORDING setelah angka 1.
- Haptic setiap angka berganti memberikan feedback tactile yang menyenangkan.
- Animasi harus **smooth** dan **tidak menyuliti** — user harus fokus siap merekam setelah countdown.