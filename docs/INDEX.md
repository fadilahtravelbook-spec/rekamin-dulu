# 📚 Rekamin Dulu - Documentation Index

## Overview
Complete UI/UX specification for "Rekamin by Bangdillah210" application with Material 3 design, ungu/blue color palette, and creator/gamer aesthetic.

## Documentation Structure

### 📖 Main Specifications
| File | Description | Lines |
|------|-------------|-------|
| `DESIGN_SPECIFICATION.md` | Full design principles, color tokens, typography, all screen states | ~3942 |
| `implementation_order.md` | Prioritized implementation roadmap P0-P3 with estimates | ~2340 |
| `INDEX.md` | This file - maps all documentation contents | - |

### 📱 Screen Specifications (12 Files)
| File | Screen | Key Features |
|------|--------|-------------|
| `01_splash.md` | Splash Screen | 1s branding, fade-in logo, auto-transition |
| `02_onboarding.md` | Onboarding (3 slides) | Edukasi izin & fitur, swipe horizontal, skip option |
| `03_home.md` | Home Screen | FAB rekam, toggle mikrofon, preview area, badge count |
| `04_permission_dialog.md` | Dialog Izin | Alur 3 izin: RECORD_AUDIO → POST_NOTIFICATIONS → MediaProjection |
| `05_countdown.md` | Countdown Screen | 3→2→1 animasi, ring progress, haptic per angka |
| `06_recording_state.md` | Recording State | Notifikasi persistens, timer, tombol Jeda/Stop |
| `07_home_recording.md` | Home saat Merekam | Timer berkedip, tombol Jeda/Stop, state khusus |
| `08_save_transition.md` | Simpan & Toast | Stop → Menyimpan → ✓ Tersimpan → Library |
| `09_library.md` | Library | Grid kartu, search bar, bottom sheet menu (ganti/nama/bagikan/hapus) |
| `10_player.md` | Player | Putar video, seek bar, auto-hide kontrol, fullscreen |
| `11_settings.md` | Settings | Video, Audio, Perilaku, Tampilan, Tentang |
| `12_edge_cases.md` | Edge Cases | 9 kasus khusus (izin dicabut, storage penuh, restart HP, dll) |

### 🎨 Resource Files (Android)
| File | Path | Description |
|------|------|-------------|
| `colors.xml` | `app/src/main/res/values/colors.xml` | Palet: Ungu `#6C5CE7`, Oranye `#FF9F43`, Merah `#E53935` |
| `themes.xml` | `app/src/main/res/values/themes.xml` | Theme Material3.DayNight + Light/Dark variants |
| `dimens.xml` | `app/src/main/res/values/dimens.xml` | Radius, spacing 8dp, typografi scale |
| `strings.xml` | `app/src/main/res/values/strings.xml` | 200+ string Bahasa Indonesia |

## 🎨 Color Palette Summary
- **Primary:** `#6C5CE7` (Ungu - mikrofon)
- **Primary Gradient:** `#8E2DE2` → `#4A00E0` (untuk tombol FAB)
- **Accent/Highlight:** `#FF9F43` (Oranye - gelombang suara)
- **Recording/Stop:** `#E53935` (Merah - universal)
- **Background Light:** `#F5F6FA` (Putih abu-muda)
- **Background Dark:** `#12121A` (Hitam kebiruan)
- **Text Primary:** `#2D3436` (Charcoal)
- **Text Secondary:** `#636E72` (Abu-abu)

## 📐 Design Tokens
- **Radius:** 16dp (kartu), 28dp (FAB/tombol besar)
- **Spacing:** Grid 8dp (4, 8, 12, 16, 24, 32, 48, 64dp)
- **Typography:** Poppins/Montserrat untuk heading, Inter/Roboto untuk body
- **Elevation:** FAB 4dp normal, 6dp pressed

## 🚀 Implementation Roadmap
| Prioritas | Halaman | Estimasi |
|-----------|---------|----------|
| **P0** | Home + Tombol Rekam | 1 hari |
| **P0** | Dialog Izin + Countdown | 1 hari |
| **P0** | Notifikasi Recording | 1 hari |
| **P1** | Library (list + empty state) | 1 hari |
| **P1** | Player | 1 hari |
| **P2** | Settings | 1 hari |
| **P2** | Splash | 2 jam |
| **P3** | Onboarding | 2 jam |
| **P3** | Polish (animasi, haptic) | 1 hari |
| **Total UI** | ~ | **6-7 hari** |

## 📱 Total Documentation
- **14 file** utama (2 docs + 12 layar)
- **4 file** resource Android
- **11 layar** utama + edge cases
- **Estimasi total:** ~6-7 hari UI saja (belum termasuk logic service)
- **Distribution:** Aplikasi ini didistribusikan melalui APK manual (sideloading), bukan Play Store. Untuk instalasi ke device lain, baca panduan di `docs/screens/12_edge_cases.md`.

## 🛠️ Cara Menggunakan Dokumentasi
1. Baca `DESIGN_SPECIFICATION.md` untuk prinsip umum
2. Lihat `implementation_order.md` untuk urutan kerja
3. Referensikan `01_splash.md` hingga `12_edge_cases.md` untuk detail tiap layar
4. Gunakan resource di `app/src/main/res/values/` untuk implementasi kode
5. Konsultasikan `INDEX.md` jika perlu reference cepat

---
*Last Updated: October 2026*
*Application: Rekamin by Bangdillah210*
*Platform: Android 7.0+*