# � Recording State (tidak ada layar aplikasi)

**Tujuan:** Rekaman berjalan, kontrol via notifikasi persistens.

## Yang Terjadi pada User

```
[Home hilang] → [Layar kembali normal] → [Notifikasi muncul]
```

User meninggalkan Home screen, layar utama HP kembali normal (bisa pakai app lain), dan notifikasi persistens muncul di status bar.

---

## Notifikasi Persisten

### Desain Notifikasi

```
┌───────────────────────────────────┐
│ 🔴 Rekamin Dulu              00:15 │   ← Timer bergerak mundur
│    Merekam layar...               │
│                                   │
│  [⏸ Jeda]        [⏹ Stop]        │   ← Dua tombol aksi
└───────────────────────────────────┘
```

### State Notifikasi

| State | Teks Notifikasi | Aksi Tombol |
|-------|-----------------|-------------|
| **Recording** | "Merekam... 00:15" | ⏸ Jeda, ⏹ Stop |
| **Paused** | "Dijeda • 00:15" | ▶ Lanjut, ⏹ Stop |
| **Saving** | "Menyimpan rekaman..." | (disabled — tidak bisa tap) |

### Perilaku Notifikasi

| Perilaku | Deskripsi |
|----------|-----------|
| **Persisten** | Tidak bisa di-swipe away saat merekam (system flag ongoing) |
| **Tap notifikasi** | Buka **Home** (menampilkan status "Sedang merekam") |
| **Quick Settings** | Kontrol tetap dapat diakses dari Quick Settings panel |
| **Timer** | Mundur dari 00:00, update setiap detik |
| **Pausing** | Menghitung waktu terpisah (jika dipause 2 detik, total tetap +2 detik) |

### Warna & Typography

| Elemen | Nilai |
|--------|-------|
| Judul "Rekamin Dulu" | Warna primary #D32F2F, 16sp Bold |
| Timer "00:15" | Warna merah #D32F2F, 24sp SemiBold |
| Teks "Merekam layar..." | Warna onSurface, 14sp Regular |
| Tombol Jeda/Stop | Warna appropriate, tinggi 32dp |
| Latar | Surface dengan elevation 4dp |

### Tombol Aksi

| Tombol | Warna | Teks | Deskripsi |
|--------|-------|------|-----------|
| ⏸ Jeda | Abu-abu | "Jeda" | Pause rekaman |
| ▶ Lanjut (saat paused) | Primary | "Lanjut" | Lanjutkan rekaman |
| ⏹ Stop | Merah #B00020 | "Hentikan" | Hentikan dan simpan |

---

## UX Notes

- Notifikasi **tidak bisa di-swipe** saat merekam (persistent ongoing notification).
- **Tap notifikasi** → buka **Home** (menampilkan state "Sedang merekam", bukan Home default).
- Jika user membuka **Quick Settings** → tetap bisa kontrol (tombol Jeda/Stop visible).
- **Pause** menghitung waktu terpisah dari total waktu rekaman.
- Durasi timer: max 24 jam, setelah itu auto-stop + alert "Rekaman terlalu lama".
- Notifikasi tetap tampil meskipun user lock screen HP.

## Edge Cases

| Kasus | Perilaku |
|-------|-----------|
| User tutup notifikasi | Service still running, notifikasi muncul kembali jika user pull down shade |
| Panggilan masuk | MediaRecorder tetap jalan, notifikasi tetap, tapi audio mungkin konflik |
| Low storage | Notifikasi error: "Penyimpanan penuh, rekaman dihentikan" |
| HP restart | Service mati, file mungkin rusak → notifikasi di-launch ulang dengan pesan |

## Dependencies

- `colors.xml`: primary, warning (#B00020) untuk tombol stop
- `dimens.xml`: notification height ~ 60dp, button width ~ 48dp, text size
- `strings.xml`: "merekam", "dijeda", "lanjut", "hentikan", "menyimpan", "waktu_rekam"
- Ongoing notification channel: "rekamin_dulu_recording" dengan priority tinggi
- MediaProjection result handling