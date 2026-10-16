# CrossSync ProGuard Rules

# ---- Kotlin / Coroutines ----
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ---- Hilt / Dagger ----
-dontwarn com.google.errorprone.annotations.**

# ---- Room ----
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers @androidx.room.Entity class * { *; }

# ---- CrossSync Protocol ----
# Keep enum entries used via reflection in MessageType.fromCode()
-keepclassmembers enum com.crosssync.app.protocol.MessageType {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers enum com.crosssync.app.protocol.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---- Keep entity classes ----
-keep class com.crosssync.app.data.local.entity.** { *; }
-keep class com.crosssync.app.service.DiscoveredDevice { *; }
