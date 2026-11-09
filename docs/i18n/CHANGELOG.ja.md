# AEmulator 0.0.0.3

[🇬🇧 English](../../CHANGELOG.md) · [🇷🇺 Русский](CHANGELOG.ru.md) · [🇺🇦 Українська](CHANGELOG.uk.md) · [🇩🇪 Deutsch](CHANGELOG.de.md) · [🇫🇷 Français](CHANGELOG.fr.md) · [🇪🇸 Español](CHANGELOG.es.md) · [🇧🇷 Português](CHANGELOG.pt-BR.md) · [🇮🇹 Italiano](CHANGELOG.it.md) · [🇵🇱 Polski](CHANGELOG.pl.md) · [🇹🇷 Türkçe](CHANGELOG.tr.md) · [🇸🇦 العربية](CHANGELOG.ar.md) · [🇮🇷 فارسی](CHANGELOG.fa.md) · [🇮🇳 हिन्दी](CHANGELOG.hi.md) · [🇮🇩 Indonesia](CHANGELOG.id.md) · [🇻🇳 Tiếng Việt](CHANGELOG.vi.md) · [🇨🇳 简体中文](CHANGELOG.zh-CN.md) · **🇯🇵 日本語** · [🇰🇷 한국어](CHANGELOG.ko.md)

### エミュレーション
- 新しいネイティブ arm64 エンジン：Android 5.0〜17 と GSI（8〜17）がスマホ上で直接動作。Android 2.3〜4.4 は QEMU で動作。
- 64 ビットファームウェアに対応。内部の 32 ビットプログラムは qemu-arm で動作。
- GSI を含む Android 8 に完全対応。
- zygote と mediaserver のクラッシュが大幅に減少（binder の修正、system_server のデッドロックを解消）。
- ゲストから `/system` は読み取り専用になり、プリコンパイル済みのフレームワークコードが失われなくなりました。
- Dalvik ファームウェアの初回起動を高速化（Galaxy S III：104 秒 → 59 秒）。
- トランスレーターを使わないハードウェア 32 ビット実行（AArch32）のオプション。非対応時は QEMU。
- SwiftShader による GLES 1/2/3。

### ファームウェアとメーカー
- TouchWiz：`ro.tvout.enable=false`, `ro.SecEDS.enable=false` と binder パッチ。
- Qualcomm ファームウェア：`qti_permissions.xml`, `com.qti.dpmframework.xml`, `com.qualcomm.location`, `qcrilmsgtunnel`, `dpmserviceapp`, `DiracAudioControlService`, `DiracManager` を削除。
- Infinix（AOSP / XUI / XOS）と Tecno（AOSP / HiOS）を Android のバージョンで判別。
- Sony：通知 LED のエミュレーション。
- MIUI、HTC Sense、TouchWiz、Xperia、AOSP の修正。Nexus 4, Galaxy Nexus, Galaxy S II/S III, Redmi 1S, Desire HD, One M7, Nexus 5X, Phh 9, AOSP GSI 17 で動作確認。

### インポート
- 新しい形式：7z, RAR, `.pac`, yaffs2, rfs, EROFS, `super.img`, APEX, `.tar.gz`。

### 機能
- より多くのファームウェアでインターネットが使えます。
- サウンドを修正。マイク、カメラ、加速度センサー、バイブレーションを追加。
- ナビゲーションバー：戻る、ホーム、履歴がすべてのファームウェアで動作。
- ローカルネットワーク経由の ADB と内蔵 ADB ターミナル。
- 起動中のシステムの三点メニューを修正：スクリーンショット、画面録画、VNC、SMS、機内モード、APK のインストールなど。
- 起動中のシステムでメニューボタンを移動できます。
- LED インジケーターを非表示にするオプション：新しいシステムの既定値と各システムの設定に。

### ホストサービス
- ゲストから見えるすべてのソケットは一か所（`EndpointRegistry`）で宣言され、宣言順に起動されるようになりました。サービスの一覧と現在の状態に唯一の情報源ができます。インスタンスを再起動しても、前回起動時の宣言を引き継ぎません。
- 従来のエンドポイントに加えて、制御用ソケット `aemu_bridge`（bproto2）を追加しました。プロパティ、電源、クリップボード、低メモリキラーを扱い、いずれも独自の op バイトを持ちます。従来のプロトコル（`rild`、`vold`、`dnsproxyd`、`lmkd`）は一切変更していません。
- ホストサービスはインスタンスごとに閉じた能力集合（イメージ設定の `hostCaps`）から付与されます。付与されていないサービスは `EPERM` を返し、ディスパッチ経路でリフレクションは使いません。
- リカバリモードを終了したときに、バインドされたままの input ソケットが残らなくなりました。
- `ab_hello_rep` のレイアウトを修正しました。ワイヤ上は 76 バイト（`pid` の前に 2 バイトのパディング）で、74 ではありません。今後変わったら C11 の静的アサーションでビルドが失敗します。
- `lmkd` が実際に付与できるようになりました。ホストサービスの表の外にありながら能力集合の内側にあり、これまでは何にも一致しない付与が黙って消えていました。
- ケーパビリティ集合が見えるようになりました。設定に「ホストサービス」セクションを追加し、ケーパビリティごとにスイッチを用意します。カーネルがブリッジを公開するファームウェア (Android 9 以降) でのみ表示されます。古いイメージではスイッチが何もできないためです。

### ビルド
- push のたびに GitHub Actions がビルドします。ブリッジのホストテスト（gcc、`SOCK_SEQPACKET`）、モックホストのバイト配置セルフテスト（Python）、armv7 版 `libaemushim.so` のコンパイルと `ab_call`/`ab_status`/`ab_last_len` のエクスポート確認、続いて JVM 単体テストと debug APK。
- ビルド失敗はチェックの注釈として回送されます。公開リポジトリの job ログは管理者権限がないと読めないためです。
