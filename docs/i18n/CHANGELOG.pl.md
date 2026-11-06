# AEmulator 0.0.0.3

[🇬🇧 English](../../CHANGELOG.md) · [🇷🇺 Русский](CHANGELOG.ru.md) · [🇺🇦 Українська](CHANGELOG.uk.md) · [🇩🇪 Deutsch](CHANGELOG.de.md) · [🇫🇷 Français](CHANGELOG.fr.md) · [🇪🇸 Español](CHANGELOG.es.md) · [🇧🇷 Português](CHANGELOG.pt-BR.md) · [🇮🇹 Italiano](CHANGELOG.it.md) · **🇵🇱 Polski** · [🇹🇷 Türkçe](CHANGELOG.tr.md) · [🇸🇦 العربية](CHANGELOG.ar.md) · [🇮🇷 فارسی](CHANGELOG.fa.md) · [🇮🇳 हिन्दी](CHANGELOG.hi.md) · [🇮🇩 Indonesia](CHANGELOG.id.md) · [🇻🇳 Tiếng Việt](CHANGELOG.vi.md) · [🇨🇳 简体中文](CHANGELOG.zh-CN.md) · [🇯🇵 日本語](CHANGELOG.ja.md) · [🇰🇷 한국어](CHANGELOG.ko.md)

### Emulacja
- Nowy natywny silnik arm64: Android 5.0–17 i obrazy GSI (8–17) działają bezpośrednio na telefonie; Android 2.3–4.4 przez QEMU.
- Obsługa 64-bitowych firmware; 32-bitowe programy w nich działają przez qemu-arm.
- Pełna obsługa Androida 8, w tym GSI.
- zygote i mediaserver padają znacznie rzadziej (poprawki bindera, usunięta blokada system_server).
- `/system` jest dla gościa tylko do odczytu, prekompilowany kod frameworka już nie ginie.
- Szybszy pierwszy start firmware Dalvik (Galaxy S III: 104 s → 59 s).
- Opcja sprzętowego wykonywania 32-bit (AArch32) bez translatora, w razie braku — QEMU.
- GLES 1/2/3 przez SwiftShader.

### Oprogramowanie i producenci
- TouchWiz: `ro.tvout.enable=false`, `ro.SecEDS.enable=false` i poprawki bindera.
- Firmware Qualcomm: usuwane są `qti_permissions.xml`, `com.qti.dpmframework.xml`, `com.qualcomm.location`, `qcrilmsgtunnel`, `dpmserviceapp`, `DiracAudioControlService`, `DiracManager`.
- Infinix (AOSP / XUI / XOS) i Tecno (AOSP / HiOS) rozpoznawane według wersji Androida.
- Sony: emulacja diody powiadomień.
- Poprawki dla MIUI, HTC Sense, TouchWiz, Xperia i AOSP. Przetestowano na Nexus 4, Galaxy Nexus, Galaxy S II/S III, Redmi 1S, Desire HD, One M7, Nexus 5X, Phh 9, AOSP GSI 17.

### Import
- Nowe formaty: 7z, RAR, `.pac`, yaffs2, rfs, EROFS, `super.img`, APEX, `.tar.gz`.

### Funkcje
- Internet działa w większej liczbie firmware.
- Naprawiony dźwięk; dodano mikrofon, aparat, akcelerometr i wibracje.
- Pasek nawigacji: Wstecz, Ekran główny i Ostatnie działają w każdym firmware.
- ADB w sieci lokalnej i wbudowany terminal ADB.
- Naprawione menu z trzema kropkami w uruchomionym systemie: zrzuty ekranu, nagrywanie ekranu, VNC, SMS, tryb samolotowy, instalacja APK i więcej.
- Przycisk menu można przesuwać w uruchomionym systemie.
- Opcja ukrycia wskaźnika LED: w ustawieniach domyślnych nowych systemów i dla każdego systemu.

### Usługi hosta
- Każdy gniazdo widoczne dla gościa jest teraz deklarowane w jednym miejscu (`EndpointRegistry`) i uruchamiane w kolejności deklaracji, więc lista usług i ich stan na żywo mają jedno źródło. Ponowne uruchomienie instancji nie dziedziczy już deklaracji z poprzedniego startu.
- Nowe gniazdo sterujące `aemu_bridge` (bproto2) obok dotychczasowych punktów końcowych: właściwości, zasilanie, schowek i zabójca małej pamięci, każde z własnym bajtem op. Stare protokoły (`rild`, `vold`, `dnsproxyd`, `lmkd`) pozostają nietknięte.
- Usługi hosta są przyznawane dla instancji z zamkniętego zbioru zdolności (`hostCaps` w ustawieniach obrazu); usługa nieprzyznana odpowiada `EPERM`, a ścieżka rozdysponowania nie używa refleksji.
- Tryb odzyskiwania nie zostawia już po wyjściu związanego gniazda wejścia.
- Poprawiono układ `ab_hello_rep`: rekord ma 76 bajtów na łączu (2 bajty dopełnienia przed `pid`), a nie 74. Statyczne zapewnienie C11 sprawia teraz, że kompilacja się nie powiedzie, jeśli to się znowu zmieni.
- `lmkd` można teraz naprawdę przyznać: żyje poza tabelą usług hosta, ale w zbiorze zdolności, a przyznanie, które do niczego nie pasowało, znikało wcześniej po cichu.

### Kompilacja
- GitHub Actions buduje przy każdym pushu: test hosta mostu (gcc, `SOCK_SEQPACKET`), autotest układu bajtów atrapy hosta (Python), kompilację `libaemushim.so` dla armv7 z kontrolą eksportów `ab_call`/`ab_status`/`ab_last_len`, następnie testy jednostkowe JVM i APK debug.
- Niepowodzenia kompilacji są ponownie publikowane jako adnotacje kontroli, ponieważ logi zadań w publicznym repozytorium wymagają uprawnień administratora do odczytu.
