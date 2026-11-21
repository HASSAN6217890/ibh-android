# الجسر الأصلي window.__ibhN: أسماء الدوال المعلَّمة @JavascriptInterface تُستدعى من JS، فتبقى كما هي
-keepattributes *Annotation*,SourceFile,LineNumberTable
-keepclassmembers class com.nafahatsy.ibh.NativeBridge {
    @android.webkit.JavascriptInterface public *;
}
-keep class com.nafahatsy.ibh.NativeBridge
