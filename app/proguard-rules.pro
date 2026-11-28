# R8 configuration for the release build.
#
# Compose, Hilt, Room, WorkManager, Coil, OkHttp and kotlinx.serialization
# ship their own consumer rules; the entries below cover what this app adds on
# top of them.

# ---- kotlinx.serialization -------------------------------------------------
# The wire contract with Komga and the trackers is property names, so the
# @Serializable models and their generated serializers must keep their members.
-keepclassmembers @kotlinx.serialization.Serializable class com.kmworks.kmreader.** { *; }
-keepclassmembers class com.kmworks.kmreader.**$$serializer { *; }
-keepnames class com.kmworks.kmreader.core.api.model.**

# Enum constant names are serialized verbatim; never rename them.
-keepclassmembers enum * { *; }
-keepnames enum com.kmworks.kmreader.**

# ---- Application code ------------------------------------------------------
# Keep the application's own names while still allowing unused code to shrink:
# serialized enum values, WorkManager worker lookups and stack traces all
# benefit, and the names cost little next to the library code R8 rewrites.
-keep,allowshrinking class com.kmworks.kmreader.** { *; }

# ---- Retrofit --------------------------------------------------------------
# Retrofit reflects over the generated service interfaces, so annotations,
# generic signatures and parameter names have to survive shrinking.
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,Signature
-keep,allowshrinking interface com.kmworks.kmreader.core.api.** { *; }

# ---- OkHttp ----------------------------------------------------------------
# Optional TLS providers probed at runtime; safe to be absent.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
