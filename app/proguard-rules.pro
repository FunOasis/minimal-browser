-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, Exceptions

-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class com.minimalbrowser.** { *; }

# ---------------------------------------------------------------------------
# androidx.security:security-crypto (Google Tink crypto library)
#
# Tink depends on JSR-305 annotations (javax.annotation.*) and Error
# Prone annotations (com.google.errorprone.annotations.*). Both are
# provided-scope only — they exist at compile time and are stripped
# from the classpath before packaging. R8 still sees them referenced
# in Tink's bytecode during minification and fails the release build
# with "Missing class javax.annotation.Nullable".
#
# These annotations are pure metadata: they carry no runtime
# behaviour, and Tink works identically whether they are present or
# not. Telling R8 not to warn is the officially recommended fix (see
# Google's Tink documentation). The keep rules below protect the
# crypto primitives, which Tink resolves via reflection and which
# R8 would otherwise be free to rename or remove.
# ---------------------------------------------------------------------------
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**

# ---------------------------------------------------------------------------
# Tink's remote keyset downloader (com.google.crypto.tink.util.KeysDownloader)
#
# This class downloads keysets from a Google server as part of Android
# Keystore's *remote provisioning* flow. We do not use it: the vault
# relies only on Tink's local crypto primitives via
# EncryptedSharedPreferences. But R8 still scans the class and fails
# because KeysDownloader references three third-party libraries that
# are optional Tink dependencies we do not ship:
#
#   com.google.api.client.http.*   — Google HTTP Client (transport)
#   org.joda.time.*                — Joda-Time (timestamp type)
#   com.fasterxml.jackson.*        — Jackson (JSON parsing)
#
# Silencing R8's missing-class check for these packages is safe: none
# of them is reachable from any code path the vault actually invokes.
# ---------------------------------------------------------------------------
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
-dontwarn com.fasterxml.jackson.**

-keep class com.google.crypto.tink.** { *; }
-keep class com.google.protobuf.** { *; }

-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
