# ── Keep all classes referenced from XML ─────────────────────────────────────
-keep public class com.dynamicisland.app.** { *; }

# Keep Service subclasses (referenced by Manifest)
-keep class * extends android.app.Service
-keep class * extends android.accessibilityservice.AccessibilityService
-keep class * extends android.service.notification.NotificationListenerService

# Keep custom View constructors (inflated from XML / WindowManager)
-keep class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
}

# Keep Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}

# Spring physics internals
-keep class androidx.dynamicanimation.** { *; }

# Kotlin metadata (needed for reflection-free code to work)
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Remove debug logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
