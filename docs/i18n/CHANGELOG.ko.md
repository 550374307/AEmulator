# AEmulator 0.0.0.3

[🇬🇧 English](../../CHANGELOG.md) · [🇷🇺 Русский](CHANGELOG.ru.md) · [🇺🇦 Українська](CHANGELOG.uk.md) · [🇩🇪 Deutsch](CHANGELOG.de.md) · [🇫🇷 Français](CHANGELOG.fr.md) · [🇪🇸 Español](CHANGELOG.es.md) · [🇧🇷 Português](CHANGELOG.pt-BR.md) · [🇮🇹 Italiano](CHANGELOG.it.md) · [🇵🇱 Polski](CHANGELOG.pl.md) · [🇹🇷 Türkçe](CHANGELOG.tr.md) · [🇸🇦 العربية](CHANGELOG.ar.md) · [🇮🇷 فارسی](CHANGELOG.fa.md) · [🇮🇳 हिन्दी](CHANGELOG.hi.md) · [🇮🇩 Indonesia](CHANGELOG.id.md) · [🇻🇳 Tiếng Việt](CHANGELOG.vi.md) · [🇨🇳 简体中文](CHANGELOG.zh-CN.md) · [🇯🇵 日本語](CHANGELOG.ja.md) · **🇰🇷 한국어**

### 에뮬레이션
- 새 네이티브 arm64 엔진: Android 5.0–17과 GSI(8–17)가 휴대폰에서 직접 실행되며, Android 2.3–4.4는 QEMU로 실행됩니다.
- 64비트 펌웨어 지원. 그 안의 32비트 프로그램은 qemu-arm으로 실행됩니다.
- GSI를 포함한 Android 8 완전 지원.
- zygote와 mediaserver 충돌이 크게 줄었습니다(binder 수정, system_server 교착 해결).
- 게스트에서 `/system`은 읽기 전용이 되어 미리 컴파일된 프레임워크 코드가 더 이상 사라지지 않습니다.
- Dalvik 펌웨어 첫 부팅이 빨라졌습니다(Galaxy S III: 104초 → 59초).
- 번역기 없이 하드웨어 32비트(AArch32)로 실행하는 옵션, 미지원 시 QEMU 사용.
- SwiftShader를 통한 GLES 1/2/3.

### 펌웨어 및 제조사
- TouchWiz: `ro.tvout.enable=false`, `ro.SecEDS.enable=false` 및 binder 패치.
- Qualcomm 펌웨어: `qti_permissions.xml`, `com.qti.dpmframework.xml`, `com.qualcomm.location`, `qcrilmsgtunnel`, `dpmserviceapp`, `DiracAudioControlService`, `DiracManager` 제거.
- Infinix(AOSP / XUI / XOS)와 Tecno(AOSP / HiOS)를 Android 버전으로 인식.
- Sony: 알림 LED 에뮬레이션.
- MIUI, HTC Sense, TouchWiz, Xperia, AOSP 수정. Nexus 4, Galaxy Nexus, Galaxy S II/S III, Redmi 1S, Desire HD, One M7, Nexus 5X, Phh 9, AOSP GSI 17에서 테스트.

### 가져오기
- 새 형식: 7z, RAR, `.pac`, yaffs2, rfs, EROFS, `super.img`, APEX, `.tar.gz`.

### 기능
- 더 많은 펌웨어에서 인터넷이 작동합니다.
- 사운드 수정, 마이크·카메라·가속도계·진동 추가.
- 내비게이션 바: 뒤로, 홈, 최근 앱이 모든 펌웨어에서 작동.
- 로컬 네트워크 ADB와 내장 ADB 터미널.
- 실행 중인 시스템의 점 세 개 메뉴 수정: 스크린샷, 화면 녹화, VNC, SMS, 비행기 모드, APK 설치 등.
- 실행 중인 시스템에서 메뉴 버튼을 옮길 수 있습니다.
- LED 표시등 숨기기 옵션: 새 시스템 기본값과 시스템별 설정.

### 호스트 서비스
- 게스트가 볼 수 있는 모든 소켓이 이제 한곳(`EndpointRegistry`)에서 선언되고 선언 순서대로 시작됩니다. 서비스 목록과 실시간 상태의 출처가 하나가 된 것입니다. 인스턴스를 다시 시작해도 이전 부팅의 선언을 물려받지 않습니다.
- 기존 엔드포인트 옆에 제어 소켓 `aemu_bridge`(bproto2)를 추가했습니다. 프로퍼티, 전원, 클립보드, 저메모리 킬러를 다루며 각자 자신의 op 바이트를 가집니다. 기존 프로토콜(`rild`, `vold`, `dnsproxyd`, `lmkd`)은 그대로입니다.
- 호스트 서비스는 인스턴스마다 닫힌 능력 집합(이미지 설정의 `hostCaps`)에서 부여됩니다. 부여되지 않은 서비스는 `EPERM`으로 응답하며, 디스패치 경로에는 리플렉션이 없습니다.
- 복구 모드가 종료된 뒤 바인딩된 input 소켓을 남기지 않습니다.
- `ab_hello_rep` 레이아웃을 고쳤습니다. 와이어상 레코드는 76바이트이며(`pid` 앞에 2바이트 패딩) 74가 아닙니다. 이제 다시 바뀌면 C11 정적 단언이 빌드를 실패시킵니다.
- `lmkd`도 이제 실제로 부여할 수 있습니다. 호스트 서비스 표 밖에 있지만 능력 집합 안에 있으며, 예전에는 아무것도 맞지 않는 부여가 조용히 사라졌습니다.

### 빌드
- push할 때마다 GitHub Actions가 빌드합니다. 브리지 호스트 테스트(gcc, `SOCK_SEQPACKET`), 모의 호스트 바이트 레이아웃 자체 테스트(Python), armv7용 `libaemushim.so` 컴파일과 `ab_call`/`ab_status`/`ab_last_len` 내보내기 확인, 이어서 JVM 단위 테스트와 debug APK.
- 빌드 실패는 검사 주석으로 다시 게시됩니다. 공개 저장소의 작업 로그는 관리자 권한이 있어야 읽을 수 있기 때문입니다.
