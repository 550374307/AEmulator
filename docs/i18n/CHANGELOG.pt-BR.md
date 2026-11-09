# AEmulator 0.0.0.3

[🇬🇧 English](../../CHANGELOG.md) · [🇷🇺 Русский](CHANGELOG.ru.md) · [🇺🇦 Українська](CHANGELOG.uk.md) · [🇩🇪 Deutsch](CHANGELOG.de.md) · [🇫🇷 Français](CHANGELOG.fr.md) · [🇪🇸 Español](CHANGELOG.es.md) · **🇧🇷 Português** · [🇮🇹 Italiano](CHANGELOG.it.md) · [🇵🇱 Polski](CHANGELOG.pl.md) · [🇹🇷 Türkçe](CHANGELOG.tr.md) · [🇸🇦 العربية](CHANGELOG.ar.md) · [🇮🇷 فارسی](CHANGELOG.fa.md) · [🇮🇳 हिन्दी](CHANGELOG.hi.md) · [🇮🇩 Indonesia](CHANGELOG.id.md) · [🇻🇳 Tiếng Việt](CHANGELOG.vi.md) · [🇨🇳 简体中文](CHANGELOG.zh-CN.md) · [🇯🇵 日本語](CHANGELOG.ja.md) · [🇰🇷 한국어](CHANGELOG.ko.md)

### Emulação
- Novo motor arm64 nativo: Android 5.0–17 e GSIs (8–17) rodam direto no celular; Android 2.3–4.4, via QEMU.
- Suporte a firmware de 64 bits; os programas de 32 bits dentro deles rodam via qemu-arm.
- Suporte completo ao Android 8, incluindo GSIs.
- zygote e mediaserver travam bem menos (correções no binder, deadlock do system_server corrigido).
- `/system` é somente leitura para o convidado: o código pré-compilado do framework não se perde mais.
- Primeira inicialização mais rápida de firmware Dalvik (Galaxy S III: 104 s → 59 s).
- Opção de execução 32 bits por hardware (AArch32) sem tradutor, com QEMU como alternativa.
- GLES 1/2/3 via SwiftShader.

### Firmware e fabricantes
- TouchWiz: `ro.tvout.enable=false`, `ro.SecEDS.enable=false` e patches de binder.
- Firmware Qualcomm: removidos `qti_permissions.xml`, `com.qti.dpmframework.xml`, `com.qualcomm.location`, `qcrilmsgtunnel`, `dpmserviceapp`, `DiracAudioControlService`, `DiracManager`.
- Infinix (AOSP / XUI / XOS) e Tecno (AOSP / HiOS) detectados pela versão do Android.
- Sony: emulação do LED de notificação.
- Correções para MIUI, HTC Sense, TouchWiz, Xperia e AOSP. Testado em Nexus 4, Galaxy Nexus, Galaxy S II/S III, Redmi 1S, Desire HD, One M7, Nexus 5X, Phh 9, AOSP GSI 17.

### Importação
- Novos formatos: 7z, RAR, `.pac`, yaffs2, rfs, EROFS, `super.img`, APEX, `.tar.gz`.

### Recursos
- A internet funciona em mais firmwares.
- Som corrigido; microfone, câmera, acelerômetro e vibração adicionados.
- Barra de navegação: Voltar, Início e Recentes funcionam em todos os firmwares.
- ADB pela rede local e terminal ADB integrado.
- Menu de três pontos corrigido no sistema em execução: capturas de tela, gravação de tela, VNC, SMS, modo avião, instalação de APK e mais.
- O botão de menu pode ser movido no sistema em execução.
- Opção para ocultar o indicador LED: nos padrões de novos sistemas e em cada sistema.

### Serviços do host
- Todo socket visível ao convidado agora é declarado em um só lugar (`EndpointRegistry`) e iniciado na ordem de declaração, então a lista de serviços e seu estado ao vivo têm uma única fonte. Reiniciar uma instância não herda mais as declarações da inicialização anterior.
- Novo socket de controle `aemu_bridge` (bproto2) ao lado dos endpoints existentes: propriedades, energia, área de transferência e o matador de memória baixa, cada um com seu próprio byte de operação. Os protocolos antigos (`rild`, `vold`, `dnsproxyd`, `lmkd`) ficam intocados.
- Os serviços do host são concedidos por instância a partir de um conjunto fechado de capacidades (`hostCaps` nas configurações da imagem); um serviço não concedido responde `EPERM`, e o caminho de despacho não usa reflexão.
- O modo de recuperação não deixa mais um socket de entrada vinculado depois de sair.
- Corrigido o layout de `ab_hello_rep`: o registro tem 76 bytes no fio (2 bytes de preenchimento antes de `pid`), não 74. Uma asserção estática de C11 agora quebra a compilação se ele mudar de novo.
- O `lmkd` agora pode de fato ser concedido: ele vive fora da tabela de serviços do host, mas dentro do conjunto de capacidades, e uma concessão que não casava com nada desaparecia em silêncio.
- O conjunto de capacidades agora está visível: as configurações têm uma seção Serviços do host com um interruptor por capacidade, apenas para firmwares cujo kernel exporta a ponte (Android 9+), porque em imagens mais antigas os interruptores não fariam nada.

### Compilação
- O GitHub Actions compila a cada push: o teste de host da ponte (gcc, `SOCK_SEQPACKET`), o autoteste de layout de bytes do host simulado (Python), uma compilação armv7 de `libaemushim.so` com verificação de exportação de `ab_call`/`ab_status`/`ab_last_len`, depois os testes unitários de JVM e um APK de depuração.
- As falhas de compilação são republicadas como anotações de verificação, porque os logs de job em um repositório público exigem direitos de administrador para serem lidos.
