# AEmulator 0.0.0.3

[🇬🇧 English](../../CHANGELOG.md) · [🇷🇺 Русский](CHANGELOG.ru.md) · [🇺🇦 Українська](CHANGELOG.uk.md) · [🇩🇪 Deutsch](CHANGELOG.de.md) · [🇫🇷 Français](CHANGELOG.fr.md) · [🇪🇸 Español](CHANGELOG.es.md) · [🇧🇷 Português](CHANGELOG.pt-BR.md) · [🇮🇹 Italiano](CHANGELOG.it.md) · [🇵🇱 Polski](CHANGELOG.pl.md) · [🇹🇷 Türkçe](CHANGELOG.tr.md) · [🇸🇦 العربية](CHANGELOG.ar.md) · [🇮🇷 فارسی](CHANGELOG.fa.md) · [🇮🇳 हिन्दी](CHANGELOG.hi.md) · [🇮🇩 Indonesia](CHANGELOG.id.md) · **🇻🇳 Tiếng Việt** · [🇨🇳 简体中文](CHANGELOG.zh-CN.md) · [🇯🇵 日本語](CHANGELOG.ja.md) · [🇰🇷 한국어](CHANGELOG.ko.md)

### Giả lập
- Engine arm64 gốc mới: Android 5.0–17 và GSI (8–17) chạy trực tiếp trên điện thoại; Android 2.3–4.4 chạy qua QEMU.
- Hỗ trợ firmware 64-bit; các chương trình 32-bit bên trong chạy qua qemu-arm.
- Hỗ trợ đầy đủ Android 8, bao gồm GSI.
- zygote và mediaserver ít bị crash hơn nhiều (sửa binder, khắc phục treo system_server).
- `/system` chỉ đọc với máy khách nên mã framework đã biên dịch sẵn không còn bị mất.
- Khởi động lần đầu firmware Dalvik nhanh hơn (Galaxy S III: 104 giây → 59 giây).
- Tùy chọn chạy 32-bit bằng phần cứng (AArch32) không qua trình dịch, dự phòng bằng QEMU.
- GLES 1/2/3 qua SwiftShader.

### Firmware và hãng
- TouchWiz: `ro.tvout.enable=false`, `ro.SecEDS.enable=false` và bản vá binder.
- Firmware Qualcomm: gỡ bỏ `qti_permissions.xml`, `com.qti.dpmframework.xml`, `com.qualcomm.location`, `qcrilmsgtunnel`, `dpmserviceapp`, `DiracAudioControlService`, `DiracManager`.
- Infinix (AOSP / XUI / XOS) và Tecno (AOSP / HiOS) được nhận diện theo phiên bản Android.
- Sony: giả lập đèn LED thông báo.
- Sửa lỗi cho MIUI, HTC Sense, TouchWiz, Xperia và AOSP. Đã thử trên Nexus 4, Galaxy Nexus, Galaxy S II/S III, Redmi 1S, Desire HD, One M7, Nexus 5X, Phh 9, AOSP GSI 17.

### Nhập
- Định dạng mới: 7z, RAR, `.pac`, yaffs2, rfs, EROFS, `super.img`, APEX, `.tar.gz`.

### Tính năng
- Internet hoạt động trên nhiều firmware hơn.
- Sửa âm thanh; thêm micro, camera, cảm biến gia tốc và rung.
- Thanh điều hướng: Quay lại, Màn hình chính và Gần đây hoạt động trên mọi firmware.
- ADB qua mạng nội bộ và terminal ADB tích hợp.
- Sửa menu ba chấm trong hệ thống đang chạy: chụp màn hình, quay màn hình, VNC, SMS, chế độ máy bay, cài APK và nhiều hơn.
- Có thể di chuyển nút menu trong hệ thống đang chạy.
- Tùy chọn ẩn đèn báo LED: trong mặc định cho hệ thống mới và cho từng hệ thống.

### Dịch vụ máy chủ
- Mọi socket mà khách nhìn thấy nay được khai báo ở một nơi duy nhất (`EndpointRegistry`) và khởi động theo thứ tự khai báo, nên danh sách dịch vụ và trạng thái trực tiếp của chúng có một nguồn duy nhất. Khởi động lại một phiên không còn thừa hưởng khai báo của lần khởi động trước.
- Socket điều khiển mới `aemu_bridge` (bproto2) bên cạnh các điểm cuối cũ: thuộc tính, nguồn, clipboard và bộ diệt bộ nhớ thấp, mỗi cái mang byte op riêng. Các giao thức cũ (`rild`, `vold`, `dnsproxyd`, `lmkd`) giữ nguyên.
- Dịch vụ máy chủ được cấp theo từng phiên từ một tập năng lực đóng (`hostCaps` trong cài đặt ảnh); dịch vụ không được cấp trả `EPERM`, và đường phân phối không dùng phản chiếu.
- Chế độ phục hồi không còn để lại socket nhập đang gắn sau khi thoát.
- Đã sửa bố cục `ab_hello_rep`: bản ghi là 76 byte trên đường truyền (2 byte đệm trước `pid`), không phải 74. Khẳng định tĩnh C11 giờ làm hỏng bản dựng nếu nó lại thay đổi.
- `lmkd` nay thực sự cấp được: nó nằm ngoài bảng dịch vụ máy chủ nhưng trong tập năng lực, và trước đây một cấp phép không khớp gì thì biến mất lặng lẽ.
- Tập quyền năng giờ đã hiển thị: phần cài đặt có mục Dịch vụ máy chủ với một công tắc cho mỗi quyền năng, chỉ hiện với firmware mà nhân của nó xuất ra cầu nối (Android 9 trở lên), vì trên ảnh cũ hơn các công tắc sẽ không làm được gì.

### Bản dựng
- GitHub Actions dựng ở mỗi lần push: kiểm thử máy chủ của cầu (gcc, `SOCK_SEQPACKET`), tự kiểm bố cục byte của máy chủ giả (Python), biên dịch `libaemushim.so` cho armv7 kèm kiểm tra xuất `ab_call`/`ab_status`/`ab_last_len`, rồi kiểm thử đơn vị JVM và APK gỡ lỗi.
- Lỗi dựng được đăng lại dưới dạng chú thích kiểm tra, vì nhật ký công việc trên kho công khai cần quyền quản trị mới đọc được.
