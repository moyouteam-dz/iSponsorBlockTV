# iSponsorBlockTV Android — إصدار تجريبي أصلي (Kotlin)

تطبيق أندرويد أصلي يطبّق الوظائف الأساسية لمشروع [dmunozv04/iSponsorBlockTV](https://github.com/dmunozv04/iSponsorBlockTV) (GNU GPLv3)، دون تشغيل Python داخل التطبيق أو الاعتماد على حاسوب أو خادم مستقل أثناء الاستعمال.

**الهدف:** تثبيت التطبيق على هاتف Android أو جهاز Android TV / Fire TV، ثم ربطه بتطبيق **YouTube for TV الرسمي** باستعمال رمز التلفاز. أثناء تشغيل الخدمة واتصال الجهاز بالإنترنت يتابع تشغيل الفيديو عبر YouTube Lounge ويستعمل SponsorBlock لتجاوز المقاطع المُبلّغ عنها. كما يمكنه كتم الإعلانات أو إرسال أمر تخطي الإعلان **عندما يسمح YouTube بذلك**.

## ما يتوفر في النسخة 0.1.0

- واجهة عربية داكنة، تعمل باللمس أو جهاز التحكم (D-pad).
- إدخال رمز YouTube TV من الإعدادات ← «الربط برمز التلفاز».
- حفظ Screen ID محليًا (من دون تخزين كلمة مرور أو ربط حساب Google).
- الاتصال/إعادة الاتصال ببروتوكول YouTube Lounge الرسمي المستخدم في `pyytlounge`.
- قراءة مقاطع SponsorBlock بواسطة SHA-256 prefix على خادم SponsorBlock؛ ودمج المقاطع المتداخلة وإرسال `seekTo`.
- اختيار الفئات (sponsor, intro, outro, selfpromo وغيرها)، وإعداد الحد الأدنى وطول التأخير.
- كتم الإعلانات مؤقتًا، وأمر Skip Ad للإعلانات القابلة للتخطي.
- Foreground Service مع إشعار مستمر وزر إيقاف، دون صلاحيات Root أو Accessibility أو VPN.
- اختبار وحدات للمنطق الأساسي وقراءة رسائل Lounge.

## طريقة الاستعمال

1. افتح تطبيق **YouTube** الرسمي على التلفاز (LG / Samsung / Android TV / Fire TV إلخ).
2. من إعدادات YouTube اختر **Link with TV code / الربط باستخدام رمز التلفاز**.
3. افتح تطبيق iSponsorBlockTV Android، أدخل الرمز الرقمي واضغط **ربط التلفاز**.
4. شغّل الخدمة من زر **تشغيل**، ثم افتح فيديو على التلفاز. يحتفظ الجهاز المضيف (الهاتف أو Android TV) باتصال الإنترنت.
5. لإيقاف الخدمة استخدم زر «إيقاف» في الواجهة أو زر الإيقاف في إشعار النظام.

> عند تثبيت التطبيق على نفس جهاز Android TV الذي يشغّل YouTube، يجب أن تظل خدمة المراقبة في الخلفية وأن يدعم إصدار YouTube TV عليه بروتوكول Lounge. الأنظمة قد تقيد الخدمات في الخلفية. لا يلزم أن يكون الهاتف والتلفاز في نفس الشبكة بعد إتمام الربط اليدوي، لكن كلاهما يحتاج الإنترنت.

## بناء APK

### GitHub Actions (الأفضل عند عدم وجود Android Studio)

1. ارفع **محتويات هذا المجلد** إلى مستودع GitHub خاص بك. لا ترفع ملف ZIP نفسه فقط؛ يجب أن تظهر `settings.gradle.kts` و`app/` و`.github/workflows/android.yml` في جذر المستودع.
2. من GitHub اختر **Actions → Android CI → Run workflow**، أو ارفع الملفات على `main` لبدء البناء تلقائيًا.
3. بعد نجاح المهمة افتح تفاصيلها، ثم **Artifacts → iSponsorBlockTV-Android-debug** لتحميل الأرشيف الذي يحتوي على `app-debug.apk`.
4. ثبّت APK على جهاز Android (قد تحتاج السماح بالتثبيت من مصدر آخر).

### Android Studio

- Android Studio مع **JDK 17** وAndroid SDK Platform **35**.
- Android Gradle Plugin **8.7.2**، Gradle **8.9**، Kotlin **2.1.0**، Compose Material3، Hilt.
- افتح المجلد، مزامنة Gradle، ثم Build → Build APK(s).
- من سطر الأوامر مع Gradle مثبت: `gradle testDebugUnitTest assembleDebug`.
- الاختبار المحلي للخوارزمية (إن توفر Kotlin CLI): `./tools/test-core.sh`.

**مهم:** لا يوجد في هذا الأرشيف ملف APK مُجمّع. البيئة التي أُنشئ فيها المشروع لا تحتوي على Android SDK/Gradle، ولذلك لم يُختبر البناء أو التشغيل على هاتف/تلفاز فعلي. ملف GitHub Actions موجود لتولّي التجميع والاختبارات عند رفع المشروع إلى GitHub. **لا تعتبر هذه نسخة إنتاجية قبل بناء APK وتجربة الربط والتخطي على التلفاز.**

## الخصوصية والأمان

- كل اتصالات Lounge وSponsorBlock تتم عبر HTTPS.
- يُحفظ معرّف الشاشة واسمها والإعدادات في SharedPreferences الخاص بالتطبيق، ولا تُحفظ رموز Lounge طويلة الأمد على القرص. لا يطلب التطبيق كلمة مرور YouTube.
- الاتصال بـSponsorBlock يرسل بادئة تجزئة فيديو SHA-256، بينما عدّ عمليات التخطي قد يرسل معرّفات المقاطع إلى SponsorBlock (يمكن تعطيله).
- خدمة Foreground مرتبطة بوجود اتصال بشاشة خارجية، مع صلاحية `FOREGROUND_SERVICE_CONNECTED_DEVICE` و`CHANGE_NETWORK_STATE` طبقًا لمتطلبات Android 14+.
- لا توجد خوادم أو تحليلات خاصة بالمطور ولا إعلانات داخل التطبيق.

## الوظائف غير المنفّذة / حدود التقنية

- هذه **إعادة تنفيذ Android Kotlin**، وليست منفذًا مطابقًا للبرنامج Python الأصلي.
- أول إصدار يدعم الربط اليدوي **بتلفاز واحد**، ولا ينفّذ الكشف الآلي SSDP/Chromecast أو قائمة الأجهزة المتعددة.
- قائمة الاستثناءات حسب قناة YouTube وYouTube Data API غير متوفرة بعد.
- تجاوز الإعلانات التي لا يمكن تخطيها غير مضمون؛ التطبيق يرسل أمر Skip Ad فقط عندما يعلن YouTube إمكانية التخطي. ويمكن كتم الصوت أثناء الإعلان حسب الإعدادات.
- YouTube Shorts والملفات الشخصية للأطفال (YouTube Kids) لها قيود من مصدر البيانات.
- بروتوكول YouTube Lounge غير مضمون الاستقرار؛ قد تتسبب تغييرات Google في تعطيل الربط أو الأحداث. لم تُتح فرصة اختبار التكامل ضد التلفاز فعليًا في هذه البيئة.
- تختلف سياسات الطاقة والتشغيل بالخلفية باختلاف مصنّع الجهاز.

## هيكلة المشروع

- `app/src/main/java/.../domain`: منطق دمج المقاطع وجدولتها، قارئ إطارات Lounge.
- `.../data`: إعدادات محلية، HTTPS، رمز الربط، SponsorBlock API، Lounge client.
- `.../service`: Foreground Service وحلقة الاتصال والأحداث.
- `.../ui`: Jetpack Compose + ViewModel للحالة.
- `.github/workflows/android.yml`: بناء واختبار وإخراج Debug APK تلقائيًا.
- `tools/test-core.sh`: فحص محلي للخوارزمية بلا Android SDK.

## مصدر المشروع والترخيص

الاسم iSponsorBlockTV مستلهم من [dmunozv04/iSponsorBlockTV](https://github.com/dmunozv04/iSponsorBlockTV) وبرمجية [FabioGNR/pyytlounge](https://github.com/FabioGNR/pyytlounge). البروتوكول ومنطق التخطي مستندان إلى مشروعيهما المفتوحين تحت رخصة GNU GPLv3. هذا المشروع مشتق وموزع كذلك تحت **GNU General Public License v3.0**؛ نص الترخيص الكامل في `LICENSE`. الأسماء والعلامات التجارية الأخرى محفوظة لأصحابها، والتطبيق غير تابع إلى YouTube/Google أو SponsorBlock.
