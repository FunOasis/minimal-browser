-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, Exceptions

-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class com.minimalbrowser.** { *; }

-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
