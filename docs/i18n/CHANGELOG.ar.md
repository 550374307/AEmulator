# AEmulator 0.0.0.3

[🇬🇧 English](../../CHANGELOG.md) · [🇷🇺 Русский](CHANGELOG.ru.md) · [🇺🇦 Українська](CHANGELOG.uk.md) · [🇩🇪 Deutsch](CHANGELOG.de.md) · [🇫🇷 Français](CHANGELOG.fr.md) · [🇪🇸 Español](CHANGELOG.es.md) · [🇧🇷 Português](CHANGELOG.pt-BR.md) · [🇮🇹 Italiano](CHANGELOG.it.md) · [🇵🇱 Polski](CHANGELOG.pl.md) · [🇹🇷 Türkçe](CHANGELOG.tr.md) · **🇸🇦 العربية** · [🇮🇷 فارسی](CHANGELOG.fa.md) · [🇮🇳 हिन्दी](CHANGELOG.hi.md) · [🇮🇩 Indonesia](CHANGELOG.id.md) · [🇻🇳 Tiếng Việt](CHANGELOG.vi.md) · [🇨🇳 简体中文](CHANGELOG.zh-CN.md) · [🇯🇵 日本語](CHANGELOG.ja.md) · [🇰🇷 한국어](CHANGELOG.ko.md)

<div dir="rtl">

### المحاكاة
- محرك arm64 أصلي جديد: يعمل أندرويد 5.0–17 وصور GSI ‏(8–17) مباشرة على الهاتف، ويعمل أندرويد 2.3–4.4 عبر QEMU.
- دعم البرامج الثابتة 64 بت، وتعمل برامج 32 بت داخلها عبر qemu-arm.
- دعم كامل لأندرويد 8، بما في ذلك صور GSI.
- انهيارات zygote وmediaserver أقل بكثير (إصلاحات binder وإصلاح توقف system_server).
- `/system` للقراءة فقط داخل النظام الضيف، فلم تعد شيفرة الإطار المترجمة مسبقًا تضيع.
- إقلاع أول أسرع لبرامج Dalvik الثابتة (Galaxy S III: ‏104 ث ← 59 ث).
- خيار التنفيذ العتادي 32 بت (AArch32) دون مترجم، مع QEMU بديلًا.
- GLES 1/2/3 عبر SwiftShader.

### البرامج الثابتة والشركات المصنعة
- TouchWiz: ‏`ro.tvout.enable=false`, `ro.SecEDS.enable=false` وتصحيحات binder.
- برامج Qualcomm الثابتة: تُحذف `qti_permissions.xml`, `com.qti.dpmframework.xml`, `com.qualcomm.location`, `qcrilmsgtunnel`, `dpmserviceapp`, `DiracAudioControlService`, `DiracManager`.
- يُتعرَّف على Infinix ‏(AOSP / XUI / XOS) وTecno ‏(AOSP / HiOS) حسب إصدار أندرويد.
- Sony: محاكاة مصباح الإشعارات.
- إصلاحات لـ MIUI وHTC Sense وTouchWiz وXperia وAOSP. جرى الاختبار على Nexus 4, Galaxy Nexus, Galaxy S II/S III, Redmi 1S, Desire HD, One M7, Nexus 5X, Phh 9, AOSP GSI 17.

### الاستيراد
- صيغ جديدة: 7z, RAR, `.pac`, yaffs2, rfs, EROFS, `super.img`, APEX, `.tar.gz`.

### الميزات
- الإنترنت يعمل في برامج ثابتة أكثر.
- إصلاح الصوت، وإضافة الميكروفون والكاميرا ومقياس التسارع والاهتزاز.
- شريط التنقل: الرجوع والرئيسية والتطبيقات الأخيرة تعمل في كل البرامج الثابتة.
- ADB عبر الشبكة المحلية وطرفية ADB مدمجة.
- إصلاح قائمة النقاط الثلاث في النظام العامل: لقطات الشاشة وتسجيل الشاشة وVNC والرسائل ووضع الطيران وتثبيت APK وغيرها.
- يمكن تحريك زر القائمة في النظام العامل.
- خيار لإخفاء مؤشر LED: في الإعدادات الافتراضية للأنظمة الجديدة ولكل نظام.

</div>

### خدمات المضيف
- أصبح كل مقبس يراه الضيف مُعلَنًا في مكان واحد (`EndpointRegistry`) ويُشغَّل بترتيب الإعلان، فصار لقائمة الخدمات وحالتها الحيّة مصدر واحد. إعادة تشغيل نسخة لم تعد ترث إعلانات التشغيل السابق.
- مقبس تحكّم جديد `aemu_bridge` (bproto2) إلى جانب نقاط النهاية القديمة: الخصائص والطاقة والحافظة وقاتل الذاكرة المنخفضة، ولكل منها بايت op خاص به. البروتوكولات القديمة (`rild` و`vold` و`dnsproxyd` و`lmkd`) لم تُمسّ.
- تُمنح خدمات المضيف لكل نسخة من مجموعة قدرات مغلقة (`hostCaps` في إعدادات الصورة)؛ والخدمة غير الممنوحة تُجيب بـ `EPERM`، ومسار التوزيع لا يستخدم أي انعكاس.
- وضع الاستعادة لم يعد يترك مقبس إدخال مربوطًا بعد خروجه.
- أُصلح تخطيط `ab_hello_rep`: السجل 76 بايت على السلك (بايتا حشو قبل `pid`) وليس 74. وأصبح تأكيد C11 الساكن يُفشل البناء إن تغيّر مرة أخرى.
- أصبح `lmkd` قابلًا للمنح فعلًا: فهو خارج جدول خدمات المضيف لكنه داخل مجموعة القدرات، وكان المنح الذي لا يطابق شيئًا يختفي بصمت.

### البناء
- يبني GitHub Actions عند كل دفعة: اختبار مضيف الجسر (gcc، `SOCK_SEQPACKET`)، والاختبار الذاتي لتخطيط بايتات المضيف الوهمي (Python)، وترجمة `libaemushim.so` لإصدار armv7 مع التحقق من تصدير `ab_call`/`ab_status`/`ab_last_len`، ثم اختبارات JVM وملف APK للتشخيص.
- تُعاد نشر إخفاقات البناء كتعليقات فحص، لأن سجلات المهام في مستودع عام تحتاج صلاحيات مدير للقراءة.
