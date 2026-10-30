# � Simpan — Toast & Transisi

**Urutan saat Stop ditekan:**

```
[Stop] → [Notifikasi: "Menyimpan..."] → [1-2 detik] → [Toast: "✓ Tersimpan"] → [Library]
```

---

## 1. Saat Tombol Stop Ditekan

- MediaRecorder dihentikan segera.
- File video di-proses (encoding/optimization).
- Notifikasi berubah: "Menyimpan rekaman..." (disabled).

## 2. Notifikasi "Menyimpan..."

```
┌─────────────────────────────┐
│   Menyimpan rekaman...          │
│                                   │
│     ⏳                         │   ← Spinner animasi
└─────────────────────────────┘
```

- Durasi: 1-2 detik (waktu encoding minimal).
- Tombol tidak bisa ditap (disabled).
- Notifikasi tetap persisten tapi berubah isinya.

## 3. Toast "✓ Tersimpan"

```
┌────────────────────────────────┐
│  ✓  Rekaman tersimpan ke       │
│     Galeri › Movies ›          │
│     RekaminDulu                │
└────────────────────────────────┘
```

### Desain Toast

| Elemen | Nilai |
|--------|-------|
| Icon ✓ | Warna hijau succes |
| Teks | "Rekaman tersimpan ke Galeri › Movies › RekaminDulu" |
| Warna latar | Surface dengan sedikit elevation |
| Durasi | 3-5 detik (auto dismiss) |
| Posisi | Atas layar (top-center), jendela tidak overlap FAB |

### UX Notes

- Toast muncul **di layar Home** setelah transisi dari recording state.
- Jika user sedang di Layar lain → toast tetap muncul di Home (refresh).
- Jika **gagal simpan** (storage penuh, error encoding) → **Snackbar merah**: "Gagal menyimpan rekaman. Coba lagi."
- Toast memiliki **action button "Lihat"** (optional) → langsung ke Library.

## 4. Transisi ke Library

- Setelah toast menghilang → activity/fragment beralih ke **Library**.
- Library menampilkan rekaman baru di paling atas daftar.
- Toast dan transisi harus **smooth**, tidak ada delay yang terlalu lama.

## Warna & Typography

| Elemen | Nilai |
|--------|-------|
| Icon ✓ | Hijau #4CAF50 |
| Teks toast | Warna onSurface, 14sp Regular |
| Latar toast | Primary opacity 10% atau surface |
| Warna "Gagal" Snackbar | Merah #B00020 |

## Dependencies

- `strings.xml`: "tersimpan", "galeri", "movies", "rekamin_dulu", "gagal_simpan", "coba_lagi"
- `colors.xml`: succes (#4CAF50), error (#B00020)
- `dimens.xml`: toast height ~ 40dp, font size
- Encoding/processing logic setelah stop

## Edge Cases

| Kasus | Perilaku |
|-------|-----------|
| **Storage penuh** | Rekaman otomatis stop + Snackbar error "Penyimpanan penuh" |
| **Encoding error** | Snackbar error "Gagal menyimpan rekaman. Coba lagi." |
| **User cancel saat menyimpan** | Proses dihentikan, kembali ke Home tanpa toast |
| **Low memory** | Proses dihentikan, toast warning "Memori rendah, rekaman mungkin tidak lengkap" |