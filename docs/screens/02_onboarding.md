# � Onboarding (3 Slides)

**Tujuan:** Edukasi izin & fitur (opsional, bisa di-skip). Hanya muncul sekali.

---

## Slide 1 — Kenalan

### Layout

```
┌─────────────────────────────┐
│                         Skip│
│                             │
│        ┌─────────┐          │
│        │  🎬    │          │   ← Ilustrasi besar
│        └─────────┘          │
│                             │
│    Rekam Layar dengan       │
│       Mudah                 │   ← Heading 28sp bold
│                             │
│    Rekam semua aktivitas    │   ← Body 16sp, opacity 70%
│    layar tanpa batas waktu. │
│                             │
│                             │
│         ● ○ ○               │   ← Dot indicator (aktif di slide 1)
│                             │
│    ┌───────────────────┐    │
│    │      Lanjut       │    │   ← Button full-width
│    └───────────────────┘    │
└─────────────────────────────┘
```

### Warna & Typography

| Elemen | Nilai |
|--------|-------|
| Latar | Transparan / surface |
| Ilustrasi 🎬 | Warna abu-abu gradient |
| Heading | 28sp Bold, warna onPrimary |
| Body | 16sp Regular, opacity 70% |
| Tombol "Lanjut" | Warna primary, text putih, tinggi 48dp |
| Skip (kanan atas) | Teks abu-abu, 14sp |

### UX Notes

- **Tombol Skip** di kanan atas (teks, bukan ikon) → langsung ke Home.
- **Swipe horizontal** antar slide (disedot indicator).
- **Back sistem** = slide sebelumnya, bukan keluar dari onboarding.
- Setiap slide memiliki dot indicator: Slide 1 = ●○○, Slide 2 = ○●○, Slide 3 = ○○●
- Setelah selesai → flag `onboarding_done = true` di Prefs (SharedPreferences).

## Slide 2 — Audio

### Konten

- **Heading:** "Audio Jernih, Hasil Maksimal"
- **Body:** "Rekam mikrofon dan suara aplikasi sekaligus."
- **Ikon:** 🎤 (kiri) + 🔊 (kanan)
- **Tombol:** Lanjut (mirip slide 1)

### UX Notes

- Fokus fitur: mikrofon + audio sistem recorde bersamaan.
- Visual: dua ikon terpisah dengan garis penghubung optional.

## Slide 3 — Izin

### Layout

```
┌─────────────────────────────┐
│                         Skip│
│                             │
│    ┌───────────────────────┐│
│    │  🖥️  Tangkap Layar    ││   ← Ikon + teks
│    │  🎤  Mikrofon         ││
│    │  🔔  Notifikasi       ││
│    └───────────────────────┘│
│                             │
│   Heading: "Izin yang Dibutuhkan" │
│                             │
│   List izin (lihat di bawah)│
│                             │
│         ● ○ ○               │   ← Dot indicator (slide 3 terakhir)
│                             │
│    ┌───────────────────┐    │
│    │      Mulai        │    │   ← Tombol warna primary, full-width
│    └───────────────────┘    │
└─────────────────────────────┘
```

### Daftar Izin

1. **🖥️ Tangkap Layar** — untuk merekam layar
2. **🎤 Mikrofon** — merekam suara Anda
3. **🔔 Notifikasi** — kontrol saat merekom (Android 13+)

### UX Notes

- Penjelasan rationale jika izin pernah ditolak.
- Setiap izin memiliki ikon dan deskripsi singkat.
- Tombol "Mulai Sekarang" hijau/primary → ke Home (dengan izin request flow).
- Jika user klik Skip → langsung ke Home tanpa izin request.

## Keseluruhan Onboarding

| Elemen | Deskripsi |
|--------|-----------|
| Jumlah slide | 3 |
| Navigasi | Swipe horizontal atau tombol Lanjut |
| Skip | Kanan atas (slide 1 saja) |
| Dot indicator | 3 dot, slide aktif highlighted |
| Langsung ke Home | Jika user klik Skip atau selesai slide 3 |
| Flag simpan | `onboarding_done` di SharedPreferences |

## Dependencies

- `strings.xml`: "onboarding_skip", "onboarding_lanjut", "onboarding_mulai_sekarang", dan teks izin
- `colors.xml`: warna primary untuk tombol Mulai
- `dimens.xml`: padding horizontal 24dp, height tombol 48dp