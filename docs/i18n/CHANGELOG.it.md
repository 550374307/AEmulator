# AEmulator 0.0.0.3

[🇬🇧 English](../../CHANGELOG.md) · [🇷🇺 Русский](CHANGELOG.ru.md) · [🇺🇦 Українська](CHANGELOG.uk.md) · [🇩🇪 Deutsch](CHANGELOG.de.md) · [🇫🇷 Français](CHANGELOG.fr.md) · [🇪🇸 Español](CHANGELOG.es.md) · [🇧🇷 Português](CHANGELOG.pt-BR.md) · **🇮🇹 Italiano** · [🇵🇱 Polski](CHANGELOG.pl.md) · [🇹🇷 Türkçe](CHANGELOG.tr.md) · [🇸🇦 العربية](CHANGELOG.ar.md) · [🇮🇷 فارسی](CHANGELOG.fa.md) · [🇮🇳 हिन्दी](CHANGELOG.hi.md) · [🇮🇩 Indonesia](CHANGELOG.id.md) · [🇻🇳 Tiếng Việt](CHANGELOG.vi.md) · [🇨🇳 简体中文](CHANGELOG.zh-CN.md) · [🇯🇵 日本語](CHANGELOG.ja.md) · [🇰🇷 한국어](CHANGELOG.ko.md)

### Emulazione
- Nuovo motore arm64 nativo: Android 5.0–17 e le GSI (8–17) girano direttamente sul telefono; Android 2.3–4.4 tramite QEMU.
- Supporto ai firmware a 64 bit; i loro programmi a 32 bit girano tramite qemu-arm.
- Supporto completo ad Android 8, GSI comprese.
- zygote e mediaserver si bloccano molto meno (correzioni a binder, deadlock di system_server risolto).
- `/system` è in sola lettura per il sistema ospite: il codice precompilato del framework non va più perso.
- Primo avvio più rapido dei firmware Dalvik (Galaxy S III: 104 s → 59 s).
- Opzione di esecuzione 32 bit hardware (AArch32) senza traduttore, con QEMU come riserva.
- GLES 1/2/3 tramite SwiftShader.

### Firmware e produttori
- TouchWiz: `ro.tvout.enable=false`, `ro.SecEDS.enable=false` e patch di binder.
- Firmware Qualcomm: rimossi `qti_permissions.xml`, `com.qti.dpmframework.xml`, `com.qualcomm.location`, `qcrilmsgtunnel`, `dpmserviceapp`, `DiracAudioControlService`, `DiracManager`.
- Infinix (AOSP / XUI / XOS) e Tecno (AOSP / HiOS) riconosciuti in base alla versione di Android.
- Sony: emulazione del LED di notifica.
- Correzioni per MIUI, HTC Sense, TouchWiz, Xperia e AOSP. Testato su Nexus 4, Galaxy Nexus, Galaxy S II/S III, Redmi 1S, Desire HD, One M7, Nexus 5X, Phh 9, AOSP GSI 17.

### Importazione
- Nuovi formati: 7z, RAR, `.pac`, yaffs2, rfs, EROFS, `super.img`, APEX, `.tar.gz`.

### Funzioni
- Internet funziona in più firmware.
- Audio corretto; aggiunti microfono, fotocamera, accelerometro e vibrazione.
- Barra di navigazione: Indietro, Home e Recenti funzionano in ogni firmware.
- ADB sulla rete locale e terminale ADB integrato.
- Menu a tre puntini corretto nel sistema avviato: screenshot, registrazione dello schermo, VNC, SMS, modalità aereo, installazione APK e altro.
- Il pulsante del menu si può spostare nel sistema avviato.
- Opzione per nascondere l’indicatore LED: nelle impostazioni predefinite dei nuovi sistemi e per ogni sistema.

### Servizi host
- Ogni socket visibile all'ospite è ora dichiarata in un solo posto (`EndpointRegistry`) e avviata nell'ordine di dichiarazione, così l'elenco dei servizi e il loro stato live hanno un'unica fonte. Riavviare un'istanza non eredita più le dichiarazioni dell'avvio precedente.
- Nuova socket di controllo `aemu_bridge` (bproto2) accanto agli endpoint esistenti: proprietà, alimentazione, appunti e killer della memoria bassa, ciascuno con il proprio byte di operazione. I protocolli storici (`rild`, `vold`, `dnsproxyd`, `lmkd`) restano intatti.
- I servizi host sono concessi per istanza da un insieme chiuso di capacità (`hostCaps` nelle impostazioni dell'immagine); un servizio non concesso risponde `EPERM` e il percorso di distribuzione non usa alcuna riflessione.
- La modalità di ripristino non lascia più una socket di input ancora associata dopo l'uscita.
- Corretto il layout di `ab_hello_rep`: il record è di 76 byte sul filo (2 byte di riempimento prima di `pid`), non 74. Un'asserzione statica C11 ora fa fallire la build se dovesse cambiare di nuovo.
- `lmkd` ora può davvero essere concesso: vive fuori dalla tabella dei servizi host ma dentro l'insieme delle capacità, e una concessione che non corrispondeva a nulla spariva in silenzio.

### Compilazione
- GitHub Actions compila a ogni push: il test host del ponte (gcc, `SOCK_SEQPACKET`), l'autotest del layout dei byte dell'host finto (Python), una compilazione armv7 di `libaemushim.so` con verifica degli export `ab_call`/`ab_status`/`ab_last_len`, poi i test unitari JVM e un APK di debug.
- I fallimenti di compilazione vengono ripubblicati come annotazioni di controllo, perché i log delle job in un repository pubblico richiedono diritti di amministratore per essere letti.
