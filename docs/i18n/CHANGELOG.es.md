# AEmulator 0.0.0.3

[🇬🇧 English](../../CHANGELOG.md) · [🇷🇺 Русский](CHANGELOG.ru.md) · [🇺🇦 Українська](CHANGELOG.uk.md) · [🇩🇪 Deutsch](CHANGELOG.de.md) · [🇫🇷 Français](CHANGELOG.fr.md) · **🇪🇸 Español** · [🇧🇷 Português](CHANGELOG.pt-BR.md) · [🇮🇹 Italiano](CHANGELOG.it.md) · [🇵🇱 Polski](CHANGELOG.pl.md) · [🇹🇷 Türkçe](CHANGELOG.tr.md) · [🇸🇦 العربية](CHANGELOG.ar.md) · [🇮🇷 فارسی](CHANGELOG.fa.md) · [🇮🇳 हिन्दी](CHANGELOG.hi.md) · [🇮🇩 Indonesia](CHANGELOG.id.md) · [🇻🇳 Tiếng Việt](CHANGELOG.vi.md) · [🇨🇳 简体中文](CHANGELOG.zh-CN.md) · [🇯🇵 日本語](CHANGELOG.ja.md) · [🇰🇷 한국어](CHANGELOG.ko.md)

### Emulación
- Nuevo motor arm64 nativo: Android 5.0–17 y las GSI (8–17) se ejecutan directamente en el teléfono; Android 2.3–4.4, mediante QEMU.
- Soporte de firmware de 64 bits; sus programas de 32 bits se ejecutan con qemu-arm.
- Soporte completo de Android 8, incluidas las GSI.
- zygote y mediaserver fallan mucho menos (correcciones de binder, bloqueo de system_server corregido).
- `/system` es de solo lectura para el invitado: el código precompilado del framework ya no se pierde.
- Primer arranque más rápido del firmware Dalvik (Galaxy S III: 104 s → 59 s).
- Opción de ejecución de 32 bits por hardware (AArch32) sin traductor, con QEMU como respaldo.
- GLES 1/2/3 mediante SwiftShader.

### Firmware y fabricantes
- TouchWiz: `ro.tvout.enable=false`, `ro.SecEDS.enable=false` y parches de binder.
- Firmware Qualcomm: se eliminan `qti_permissions.xml`, `com.qti.dpmframework.xml`, `com.qualcomm.location`, `qcrilmsgtunnel`, `dpmserviceapp`, `DiracAudioControlService`, `DiracManager`.
- Infinix (AOSP / XUI / XOS) y Tecno (AOSP / HiOS) se detectan según la versión de Android.
- Sony: emulación del LED de notificaciones.
- Correcciones para MIUI, HTC Sense, TouchWiz, Xperia y AOSP. Probado en Nexus 4, Galaxy Nexus, Galaxy S II/S III, Redmi 1S, Desire HD, One M7, Nexus 5X, Phh 9, AOSP GSI 17.

### Importación
- Nuevos formatos: 7z, RAR, `.pac`, yaffs2, rfs, EROFS, `super.img`, APEX, `.tar.gz`.

### Funciones
- Internet funciona en más firmware.
- Sonido corregido; se añaden micrófono, cámara, acelerómetro y vibración.
- Barra de navegación: Atrás, Inicio y Recientes funcionan en todo el firmware.
- ADB por red local y terminal ADB integrado.
- Menú de tres puntos corregido en un sistema en ejecución: capturas, grabación de pantalla, VNC, SMS, modo avión, instalación de APK y más.
- El botón de menú se puede mover en un sistema en ejecución.
- Opción para ocultar el indicador LED: en los valores predeterminados de sistemas nuevos y por sistema.

### Servicios del host
- Todos los sockets visibles para el invitado se declaran ahora en un solo lugar (`EndpointRegistry`) y se inician en orden de declaración, así que la lista de servicios y su estado en vivo tienen una única fuente. Reiniciar una instancia ya no hereda las declaraciones del arranque anterior.
- Nuevo socket de control `aemu_bridge` (bproto2) junto a los endpoints existentes: propiedades, energía, portapapeles y el matador de poca memoria, cada uno con su propio byte de operación. Los protocolos antiguos (`rild`, `vold`, `dnsproxyd`, `lmkd`) no se tocan.
- Los servicios del host se conceden por instancia desde un conjunto cerrado de capacidades (`hostCaps` en los ajustes de la imagen); un servicio no concedido responde `EPERM`, y la ruta de despacho no usa reflexión.
- El modo de recuperación ya no deja un socket de entrada vinculado tras salir.
- Corregida la disposición de `ab_hello_rep`: el registro mide 76 bytes en el cable (2 bytes de relleno antes de `pid`), no 74. Una aserción estática de C11 ahora rompe la compilación si vuelve a cambiar.
- `lmkd` ya se puede conceder de verdad: vive fuera de la tabla de servicios del host pero dentro del conjunto de capacidades, y una concesión que no coincidía con nada desaparecía en silencio.

### Compilación
- GitHub Actions compila en cada push: la prueba de host del puente (gcc, `SOCK_SEQPACKET`), la autoprueba de disposición de bytes del host simulado (Python), una compilación armv7 de `libaemushim.so` con verificación de exportación de `ab_call`/`ab_status`/`ab_last_len`, y luego las pruebas unitarias de JVM y un APK de depuración.
- Los fallos de compilación se republican como anotaciones de comprobación, porque los registros de tareas en un repositorio público necesitan permisos de administrador para leerse.
