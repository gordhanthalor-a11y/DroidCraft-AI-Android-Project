# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /Users/.../Library/Android/sdk/tools/proguard/proguard-android.txt
-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.room.Dao *;
    @androidx.room.Entity *;
}
