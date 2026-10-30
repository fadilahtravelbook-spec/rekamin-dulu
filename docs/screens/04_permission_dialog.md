# � Dialog Izin

**Tujuan:** Minta izin sistem secara berurutan sebelum rekaman dimulai.

## Alur Izin (Urutan Wajib)

```
1. RECORD_AUDIO (jika mik aktif) → dialog sistem
2. POST_NOTIFICATIONS (Android 13+) → dialog sistem
3. MediaProjection → dialog sistem "Mulai merekam?"
```

Setiap izin request bersifat **berurutan** — harus diterima satu-satu sebelum melanjutkan ke yang berikutnya.

---

## 1. RECORD_AUDIO

- Diminta saat user tap FAB "Mulai Rekam" jika toggle mikrofon aktif.
- **Dialog sistem**: "Aplikasi ingin mendengarkan mikir Anda"
- Opsi: **Izinkan** / **Tidak Izinkan**

## 2. POST_NOTIFICATIONS (Android 13+)

- Diperlukan untuk notifikasi persisten saat merekam.
- **Dialog sistem**: "Aplikasi ingin mengirim notifikasi"
- Opsi: **Izinkan** / **Tidak Izinkan**

## 3. MediaProjection

- **Dialog sistem**: "Mulai merekam?" dengan preview layar.
- Opsi: **Start** / **Cancel**
- Hasil: `AndroidMediaProjection` API result code.

---

## Dialog Kustom (Jika Ditolak)

Jika user menolak izin salah satu, tampil dialog kustom dengan penjelasan:

```
┌─────────────────────────────┐
│                             │
│         ⚠️                  │
│                             │
│   Izin Diperlukan           │
│                             │
│   Rekamin Dulu butuh        │
│   izin mikrofon dan         │
│   notifikasi untuk         │
│   merekam dengan baik.      │
│                             │
│   ┌──────────────────┐      │
│   │  Buka Pengaturan │      │
│   └──────────────────┘      │
│                             │
│         Nanti Saja          │
└─────────────────────────────┘
```

### Elemen Dialog Kustom

| Elemen | Deskripsi |
|--------|-----------|
| Icon ⚠️ | Peringatan, warna kuning #F57F17 |
| Judul | "Izin Diperlukan" |
| Sub-judul | "Rekamin Dulu butuh izin mikrofon dan notifikasi untuk merekam dengan baik." |
| Tombol "Buka Pengaturan" | Warna primary, kembali ke Settings |
| Tombol "Nanti Saja" | Abu-abu, tutup dialog |

### Rationale Sebelum Dialog Sistem

Jika izin pernah ditolak sebelumnya, tampil **rationale dialog** sebelum muncul dialog sistem OS:

- **Tujuan:** Memberi user pemahaman mengapa izin dibutuhkan.
- **Tombol:** "Setuju & Lanjut" → langsung ke dialog sistem, "Tidak" → tutup.

---

## UX Notes

- **Penjelasan sebelum dialog sistem** jika izin pernah ditolak — mencegah user berpikir app error.
- Dialog sistem MediaProjection **tidak bisa dikustom** — milik OS, harus diterima oleh user.
- **Fallback:** jika izin notifikasi ditolak, aplikasi tetap bisa merekam, tapi kontrol hanya lewat Home (tidak bisa dari notifikasi).
- Jika user menolak semua izin → tampil banner utama di Home: "Aktifkan izin untuk fitur rekaman".
- Setiap izin di-cache: jika user pernah izin, tidak minta lagi kecuali jika di-revoke manual.

## Warna & Typography

| Elemen | Nilai |
|--------|-------|
| Latar dialog | Transparan atau surface |
| Icon ⚠️ | Kuning #F57F17 |
| Judul "Izin Diperlukan" | 20sp Bold, warna onSurface |
| Sub-judul | 14sp Regular, opacity 80% |
| Tombol "Buka Pengaturan" | Primary #D32F2F, text putih |
| Tombol "Nanti Saja" | Abu-abu #777, text putih |

## Dependencies

- `colors.xml`: warna warning (#F57F17), primary (#D32F2F)
- `strings.xml`: "izin_butuh", "buka_pengaturan", "nanti_saja", "record_audio_rationale", "post_notifications_rationale"
- Logika request permission: RuntimePermission API dengan callback result

## Alur Implementasi

1. User tap FAB Rekam → cek permission status
2. Jika belum pernah minta → minta RECORD_AUDIO pertama kalinya
3. Jika user izinkan → minta POST_NOTIFICATIONS
4. Jika user izinkan → minta MediaProjection
5. Jika semua izinkan → lanjut ke Countdown
6. Jika user tolak salah satu → tampil dialog kustom rationale
7. Jika user tolak semua → banner di Home + FAB disabled