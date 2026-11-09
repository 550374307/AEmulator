# AEmulator 0.0.0.3

[🇬🇧 English](../../CHANGELOG.md) · [🇷🇺 Русский](CHANGELOG.ru.md) · [🇺🇦 Українська](CHANGELOG.uk.md) · [🇩🇪 Deutsch](CHANGELOG.de.md) · [🇫🇷 Français](CHANGELOG.fr.md) · [🇪🇸 Español](CHANGELOG.es.md) · [🇧🇷 Português](CHANGELOG.pt-BR.md) · [🇮🇹 Italiano](CHANGELOG.it.md) · [🇵🇱 Polski](CHANGELOG.pl.md) · [🇹🇷 Türkçe](CHANGELOG.tr.md) · [🇸🇦 العربية](CHANGELOG.ar.md) · [🇮🇷 فارسی](CHANGELOG.fa.md) · [🇮🇳 हिन्दी](CHANGELOG.hi.md) · **🇮🇩 Indonesia** · [🇻🇳 Tiếng Việt](CHANGELOG.vi.md) · [🇨🇳 简体中文](CHANGELOG.zh-CN.md) · [🇯🇵 日本語](CHANGELOG.ja.md) · [🇰🇷 한국어](CHANGELOG.ko.md)

### Emulasi
- Mesin arm64 native baru: Android 5.0–17 dan GSI (8–17) berjalan langsung di ponsel; Android 2.3–4.4 lewat QEMU.
- Dukungan firmware 64-bit; program 32-bit di dalamnya berjalan lewat qemu-arm.
- Dukungan penuh Android 8, termasuk GSI.
- zygote dan mediaserver jauh lebih jarang crash (perbaikan binder, deadlock system_server diperbaiki).
- `/system` hanya-baca bagi tamu, sehingga kode framework yang sudah dikompilasi tidak lagi hilang.
- Boot pertama firmware Dalvik lebih cepat (Galaxy S III: 104 dtk → 59 dtk).
- Opsi eksekusi 32-bit perangkat keras (AArch32) tanpa penerjemah, dengan QEMU sebagai cadangan.
- GLES 1/2/3 lewat SwiftShader.

### Firmware dan vendor
- TouchWiz: `ro.tvout.enable=false`, `ro.SecEDS.enable=false` dan patch binder.
- Firmware Qualcomm: `qti_permissions.xml`, `com.qti.dpmframework.xml`, `com.qualcomm.location`, `qcrilmsgtunnel`, `dpmserviceapp`, `DiracAudioControlService`, `DiracManager` dihapus.
- Infinix (AOSP / XUI / XOS) dan Tecno (AOSP / HiOS) dikenali dari versi Android.
- Sony: emulasi LED notifikasi.
- Perbaikan untuk MIUI, HTC Sense, TouchWiz, Xperia, dan AOSP. Diuji pada Nexus 4, Galaxy Nexus, Galaxy S II/S III, Redmi 1S, Desire HD, One M7, Nexus 5X, Phh 9, AOSP GSI 17.

### Impor
- Format baru: 7z, RAR, `.pac`, yaffs2, rfs, EROFS, `super.img`, APEX, `.tar.gz`.

### Fitur
- Internet berfungsi di lebih banyak firmware.
- Suara diperbaiki; mikrofon, kamera, akselerometer, dan getaran ditambahkan.
- Bilah navigasi: Kembali, Beranda, dan Terbaru berfungsi di semua firmware.
- ADB lewat jaringan lokal dan terminal ADB bawaan.
- Menu tiga titik di sistem yang berjalan diperbaiki: tangkapan layar, rekam layar, VNC, SMS, mode pesawat, pasang APK, dan lainnya.
- Tombol menu dapat dipindahkan di sistem yang berjalan.
- Opsi menyembunyikan indikator LED: di bawaan sistem baru dan per sistem.

### Layanan host
- Setiap soket yang terlihat oleh tamu kini dideklarasikan di satu tempat (`EndpointRegistry`) dan dimulai sesuai urutan deklarasi, sehingga daftar layanan dan keadaan langsungnya punya satu sumber. Memulai ulang sebuah instans tidak lagi mewarisi deklarasi boot sebelumnya.
- Soket kendali baru `aemu_bridge` (bproto2) di samping endpoint lama: properti, daya, papan klip, dan pembunuh memori rendah, masing-masing membawa bait op sendiri. Protokol lama (`rild`, `vold`, `dnsproxyd`, `lmkd`) tidak disentuh.
- Layanan host diberikan per instans dari himpunan kemampuan tertutup (`hostCaps` di pengaturan image); layanan yang tidak diberikan menjawab `EPERM`, dan jalur pengiriman tidak memakai refleksi.
- Mode pemulihan tidak lagi meninggalkan soket input yang terikat setelah keluar.
- Tata letak `ab_hello_rep` diperbaiki: rekaman berukuran 76 bait di kabel (2 bait padding sebelum `pid`), bukan 74. Asersi statis C11 kini menggagalkan build bila berubah lagi.
- `lmkd` kini benar-benar bisa diberikan: ia berada di luar tabel layanan host tetapi di dalam himpunan kemampuan, dan pemberian yang tidak cocok dengan apa pun dulu hilang diam-diam.
- Kumpulan kemampuan kini terlihat: pengaturan punya bagian Layanan host dengan satu sakelar per kemampuan, hanya untuk firmware yang kernel-nya mengekspor jembatan (Android 9+), karena pada image lama sakelar itu tidak akan berpengaruh.

### Build
- GitHub Actions membangun pada setiap push: uji host jembatan (gcc, `SOCK_SEQPACKET`), uji mandiri tata letak bait host tiruan (Python), kompilasi `libaemushim.so` untuk armv7 dengan pemeriksaan ekspor `ab_call`/`ab_status`/`ab_last_len`, lalu uji unit JVM dan APK debug.
- Kegagalan build diterbitkan ulang sebagai anotasi pemeriksaan, karena log pekerjaan di repositori publik butuh hak admin untuk dibaca.
