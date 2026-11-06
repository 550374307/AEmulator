# AEmulator 0.0.0.3

[🇬🇧 English](../../CHANGELOG.md) · [🇷🇺 Русский](CHANGELOG.ru.md) · [🇺🇦 Українська](CHANGELOG.uk.md) · [🇩🇪 Deutsch](CHANGELOG.de.md) · [🇫🇷 Français](CHANGELOG.fr.md) · [🇪🇸 Español](CHANGELOG.es.md) · [🇧🇷 Português](CHANGELOG.pt-BR.md) · [🇮🇹 Italiano](CHANGELOG.it.md) · [🇵🇱 Polski](CHANGELOG.pl.md) · **🇹🇷 Türkçe** · [🇸🇦 العربية](CHANGELOG.ar.md) · [🇮🇷 فارسی](CHANGELOG.fa.md) · [🇮🇳 हिन्दी](CHANGELOG.hi.md) · [🇮🇩 Indonesia](CHANGELOG.id.md) · [🇻🇳 Tiếng Việt](CHANGELOG.vi.md) · [🇨🇳 简体中文](CHANGELOG.zh-CN.md) · [🇯🇵 日本語](CHANGELOG.ja.md) · [🇰🇷 한국어](CHANGELOG.ko.md)

### Emülasyon
- Yeni yerel arm64 motoru: Android 5.0–17 ve GSI’lar (8–17) doğrudan telefonda çalışır; Android 2.3–4.4 QEMU ile çalışır.
- 64 bit yazılım desteği; içlerindeki 32 bit programlar qemu-arm ile çalışır.
- GSI’lar dahil tam Android 8 desteği.
- zygote ve mediaserver çok daha seyrek çöker (binder düzeltmeleri, system_server kilitlenmesi giderildi).
- `/system` misafir için salt okunurdur; önceden derlenmiş framework kodu artık kaybolmaz.
- Dalvik yazılımlarında daha hızlı ilk açılış (Galaxy S III: 104 sn → 59 sn).
- Çevirmen olmadan donanımsal 32 bit çalıştırma (AArch32) seçeneği; desteklenmezse QEMU kullanılır.
- SwiftShader üzerinden GLES 1/2/3.

### Yazılımlar ve üreticiler
- TouchWiz: `ro.tvout.enable=false`, `ro.SecEDS.enable=false` ve binder yamaları.
- Qualcomm yazılımları: `qti_permissions.xml`, `com.qti.dpmframework.xml`, `com.qualcomm.location`, `qcrilmsgtunnel`, `dpmserviceapp`, `DiracAudioControlService`, `DiracManager` kaldırılır.
- Infinix (AOSP / XUI / XOS) ve Tecno (AOSP / HiOS) Android sürümüne göre tanınır.
- Sony: bildirim LED’i emülasyonu.
- MIUI, HTC Sense, TouchWiz, Xperia ve AOSP için düzeltmeler. Test edilenler: Nexus 4, Galaxy Nexus, Galaxy S II/S III, Redmi 1S, Desire HD, One M7, Nexus 5X, Phh 9, AOSP GSI 17.

### İçe aktarma
- Yeni biçimler: 7z, RAR, `.pac`, yaffs2, rfs, EROFS, `super.img`, APEX, `.tar.gz`.

### Özellikler
- İnternet daha fazla yazılımda çalışıyor.
- Ses düzeltildi; mikrofon, kamera, ivmeölçer ve titreşim eklendi.
- Gezinme çubuğu: Geri, Ana ekran ve Son uygulamalar her yazılımda çalışır.
- Yerel ağ üzerinden ADB ve yerleşik ADB terminali.
- Çalışan sistemdeki üç nokta menüsü düzeltildi: ekran görüntüsü, ekran kaydı, VNC, SMS, uçak modu, APK yükleme ve dahası.
- Menü düğmesi çalışan sistemde taşınabilir.
- LED göstergesini gizleme seçeneği: yeni sistem varsayılanlarında ve her sistemde.

### Ana makine hizmetleri
- Konuğun gördüğü her soket artık tek bir yerde (`EndpointRegistry`) bildiriliyor ve bildirim sırasıyla başlatılıyor; böylece hizmet listesi ile canlı durumu tek bir kaynağa sahip. Bir örneği yeniden başlatmak önceki açılışın bildirimlerini devralmıyor.
- Eski uç noktaların yanında yeni denetim soketi `aemu_bridge` (bproto2): özellikler, güç, pano ve düşük bellek öldürücüsü, her biri kendi op baytını taşıyor. Eski protokoller (`rild`, `vold`, `dnsproxyd`, `lmkd`) olduğu gibi kalıyor.
- Ana makine hizmetleri örnek başına kapalı bir yetenek kümesinden (`imaj ayarlarındaki hostCaps`) veriliyor; verilmeyen hizmet `EPERM` döndürüyor ve dağıtım yolunda hiçbir yansıma kullanılmıyor.
- Kurtarma kipinden çıkıldığında artık bağlı bir input soketi geride kalmıyor.
- `ab_hello_rep` yerleşimi düzeltildi: kayıt tel üzerinde 76 bayt (`pid` öncesinde 2 bayt dolgu), 74 değil. Artık yeniden değişirse C11 statik doğrulaması derlemeyi düşürüyor.
- `lmkd` artık gerçekten verilebiliyor: ana makine hizmet tablosunun dışında ama yetenek kümesinin içinde; önceden hiçbir şeyle eşleşmeyen bir yetki sessizce kayboluyordu.

### Derleme
- GitHub Actions her push'ta derliyor: köprü ana makine testi (gcc, `SOCK_SEQPACKET`), sahte ana makinenin bayt yerleşimi öz testi (Python), armv7 için `libaemushim.so` derlemesi ve `ab_call`/`ab_status`/`ab_last_len` dışa aktarım denetimi, ardından JVM birim testleri ve hata ayıklama APK'sı.
- Derleme hataları denetim ek açıklamaları olarak yeniden yayımlanıyor, çünkü herkese açık bir depoda iş günlüklerini okumak yönetici hakları gerektiriyor.
