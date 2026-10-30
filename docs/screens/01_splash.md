# � Splash Screen

**Durasi:** 1 detik (1000ms)
**Tujuan:** Branding + cek status onboarding

## Layout

```
┌─────────────────────────────┐
│                             │
│                             │
│         ┌───────┐           │
│         │  🔴   │           │   ← Logo: lingkaran merah + ikon kamera
│         └───────┘           │
│                             │
│     REKAMIN DULU            │   ← Nama aplikasi, bold 24sp
│     by Bangdillah210        │   ← Subtitle 12sp, opacity 60%
│                             │
│                             │
│         ● ● ●               │   ← Indikator loading (3 dot animasi)
│                             │
└─────────────────────────────┘
```

## Warna & Typography

| Elemen | Nilai |
|--------|-------|
| Latar | Transparan / warna surface |
| Logo merah | `#D32F2F` |
| Teks "REKAMIN DULU" | 24sp Bold, warna primary |
| Teks "by Bangdillah210" | 12sp, opacity 60% |
| Titik loading | Abu-abu, animasi berantai |

## Animasi

| Animasi | Durasi |
|---------|--------|
| Fade-in logo | 400ms |
| Muncul teks (delay 200ms) | 200ms setelah logo |
| Animasi dot berantai | 1.2s (loop 1x sebelum transition) |

## UX Notes

- **Tidak ada tombol** — auto-transition.
- Jika onboarding belum selesai → ke **Onboarding**; jika sudah → ke **Home**.
- **Edge case:** app dibuka saat rekaman masih aktif → cek state service, tetap ke Home (service terpisah).
- Setelah transisi, cek `Prefs.onboarding_done`:
  - `false` → ke Onboarding (3 slide)
  - `true` → ke Home

## Dependencies

- `colors.xml`: warna `#D32F2F` untuk logo
- `strings.xml`: "rekamindulu", "by_bangdillah210"
- `dimens.xml`: radius butil untuk logo circle (28dp)