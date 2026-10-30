# � Library

**Tujuan:** Daftar semua rekaman yang tersimpan.

## Layout

```
┌─────────────────────────────┐
│  ←  Library                  │   ← Back arrow + judul
│                             │
│  ┌───────────────────────┐  │
│  │ 🔍 Cari rekaman...     │  │   ← Search bar (opsional)
│  └───────────────────────┘  │
│                             │
│  Rekaman Terbaru            │   ← Section header
│  ┌───────────────────────┐  │
│  │ ┌────┐                │  │
│  │ │ ▶  │ RekaminDulu_   │  │   ← Thumbnail + judul
│  │ │    │ 20250115_1430  │  │
│  │ └────┘ 00:45 • 24MB   │  │   ← Durasi + ukuran
│  │                  ⋮    │  │   ← Menu
│  └───────────────────────┘  │
│                             │
│  ┌───────────────────────┐  │
│  │ ┌────┐                │  │
│  │ │ ▶  │ RekaminDulu_   │  │
│  │ │    │ 20250114_0900  │  │
│  │ └────┘ 01:20 • 56MB   │  │
│  │                  ⋮    │  │
│  └───────────────────────┘  │
│                             │
└─────────────────────────────┘
```

## Elemen & Interaksi

| Elemen | Aksi |
|--------|------|
| Tombol back | Kembali ke Home |
| Search bar | Filter by nama rekaman |
| Item kartu (grid) | Tap → **Player** |
| Menu ⋮ (vertical di kanan kartu) | Bottom sheet: Ganti Nama / Bagikan / Hapus |

## State

| State | Tampilan |
|-------|----------|
| **Empty** | 🎬 "Belum ada rekaman. Mulai merekam!" + tombol "Rekam Sekarang" |
| **Loading** | Skeleton shimmer (3 kartu abu) |
| **Ada data** | Grid/list kartu dengan data rekaman |
| **Error** | "Gagal memuat rekaman" + tombol "Coba lagi" |

## Item Kartu Desain

```
┌───────────────────────┐
│ ┌────┐                │
│ │ ▶  │ RekaminDulu_   │
│ │    │ 20250115_1430  │
│ │ └────┘ 00:45 • 24MB │
│ │      ⋮              │
└───────────────────────┘
```

### Sub-elemen Item

| Elemen | Deskripsi |
|--------|-----------|
| Ikon ▶ | Arrow kanan, menunjukkan bisa diputar |
| Judul | Nama file (dipotong jika terlalu panjang) |
| Tanggal | Format: "20250115" → "15 Jan 2025" |
| Durasi | "00:45" (menit:detik) |
| Ukuran | "24MB" |
| Menu ⋮ | Titik tiga vertikal, bottom sheet muncul |

## Bottom Sheet Menu ⋮

```
┌─────────────────────────────┐
│  ────                       │   ← Drag handle
│                             │
│  ✏️  Ganti Nama             │
│  ─────────────────          │
│  ↗️  Bagikan                │
│  ─────────────────          │
│  🗑️  Hapus                  │   ← Merah
│                             │
└─────────────────────────────┘
```

### Aksi Bottom Sheet

| Aksi | Deskripsi |
|------|-----------|
| ✏️ Ganti Nama | Buka dialog edit text → user ubah nama file |
| ↗️ Bagikan | `ACTION_SEND` dengan `FileProvider` → sheet aplikasi pemilih |
| 🗑️ Hapus | Dialog konfirmasi hapus permanen |

### Dialog Konfirmasi Hapus

```
┌─────────────────────────────┐
│   Hapus Rekaman?            │
│                             │
│   Rekaman ini akan dihapus  │
│   permanen. Tidak bisa      │
│   dikembalikan.             │
│                             │
│   [Batal]     [Hapus]       │
└─────────────────────────────┘
```

### Perilaku Hapus

- Jika user klik **Hapus** → file dihapus dari storage → daftar di-refresh → item menghilang.
- Jika user klik **Batal** → bottom sheet tetap tutup, tidak ada perubahan.
- **Undo** (optional): Snackbar dengan "Dibatalkan" yang bisa di-undo 3 detik.

## Empty State

```
┌─────────────────────────────┐
│                             │
│    🎬 "Belum ada rekaman.  │
│       Mulai merekam!"       │
│                             │
│       ⏩ [ Rekam Sekarang ]  │   ← FAB kecil atau tombol utama
│                             │
└─────────────────────────────┘
```

### Warna & Typography

| Elemen | Nilai |
|--------|-------|
| Judul "Library" | 22sp SemiBold, warna onSurface |
| Tombol back | Warna abu-abu, kembali ke Home |
| Search placeholder | "Cari rekaman...", 16sp, opacity 50% |
| Empty state icon 🎬 | 48sp, warna onSurface variant |
| Teks "Belum ada rekaman" | 16sp Regular, opacity 60% |
| Tombol "Rekam Sekarang" | Primary #D32F2F, text putih, tinggi 40dp |

## Warna Item Kartu

| Elemen | Nilai |
|--------|-------|
| Latar kartu | Surface #FAFAFA (Light) / #1E1E1E (Dark) |
| Judul | Warna onSurface |
| Tanggal/Durasi | Warna onSurface opacity 60% |
| Ukuran | Warna onSurface opacity 50% |
| Menu ⋮ | Ikon abu-abu |

## Dependencies

- `strings.xml`: "library", "belum_ada_rekaman", "mulai_merekam", "cari_rekaman", "ganti_nama", "bagikan", "hapus", "konfirmasi_hapus", "dibatalkan"
- `colors.xml`: warning/error untuk tombol hapus, success untuk bagikan
- `dimens.xml`: height kartu ~ 120dp, width grid 160dp, padding 8dp
- Adapter RecyclerView untuk menampilkan list rekaman
- Database/Room untuk menyimpan metadata rekaman (path, durasi, ukuran, tanggal)