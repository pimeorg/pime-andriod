# R8 rules for the Android shell.
#
# The shell has no reflective lookups of its own (no Class.forName, no
# getMethod); everything R8 must not touch comes from the JNI boundary and from
# library code that is only reachable at runtime.

# --- JNI boundary -----------------------------------------------------------
#
# app/src/main/cpp/pime_jni.cpp exports fixed symbols named after the Java
# class and method: Java_com_pimeorg_pime_PimeCore_nativeStart and friends.
# Renaming either half breaks the lookup at load time, so the whole bridge
# class keeps its name, its members and its package.
-keep class com.pimeorg.pime.PimeCore { *; }

# The same native code calls back into the Java object it was started with
# (g_bridge) through GetMethodID with these names and signatures:
#   onNativeComposition, onNativeCommit, onNativeCandidates,
#   onNativeHideCandidates, onNativeBeep, onNativeNotify, onNativeWake,
#   onNativeLog
# -keep above already pins them; this additionally protects any future native
# method anywhere in the app.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Anything the platform instantiates or binds to by name (IME service,
# launcher activity, application class) is kept by the manifest-derived rules
# that AGP generates; listed here only so the intent is visible.
-keep class com.pimeorg.pime.PimeApplication { *; }
-keep class com.pimeorg.pime.PimeImeService { *; }
-keep class com.tencent.qqpinyin.LocalImageBridge { *; }
-keep class com.pimeorg.pime.SettingsActivity { *; }
-keep class com.pimeorg.pime.VoicePermissionActivity { *; }

# --- library warnings -------------------------------------------------------
#
# commons-compress probes optional compression backends (XZ, zstd, brotli) that
# we do not ship; the speech model archive is tar+bzip2, and bzip2 support is
# part of the library itself.
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.apache.commons.compress.**
