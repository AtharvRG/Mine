# SQLCipher & AndroidX SQLite
-keep class net.zetetic.database.sqlcipher.** { *; }
-keep class androidx.sqlite.db.** { *; }
-keep class net.sqlcipher.** { *; }

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-keep,allowobfuscation,allowshrinking class kotlinx.serialization.json.**
-keep class com.anchor.core.gateway.ToolEnvelope { *; }
-keep class com.anchor.core.gateway.ToolCall { *; }
-keep class com.anchor.core.provider.ChatRequest { *; }
-keep class com.anchor.core.provider.RequestMessage { *; }
-keep class com.anchor.core.provider.ChatChunk { *; }
-keep class com.anchor.core.provider.Choice { *; }
-keep class com.anchor.core.provider.Delta { *; }
-keep class com.anchor.core.models.NodeStateSnapshot { *; }

# ADD this to the bottom of the file to protect the Models from minification crashes (B20):
-keep class com.anchor.core.** { *; }
-keep interface com.anchor.core.** { *; }
-keep class com.anchor.mine.storage.** { *; }
-keep class com.anchor.mine.export.** { *; }
-keep class com.anchor.mine.alarms.** { *; }
-keep class com.anchor.mine.MineApp { *; }

# Bouncy Castle (Export Encryption)
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Test Runner & ErrorProne annotations
-keep class androidx.test.** { *; }
-keep class androidx.tracing.** { *; }
-keep class kotlin.** { *; }
-keep class org.junit.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn androidx.test.**
-dontwarn androidx.tracing.**
-dontwarn kotlin.**
-dontwarn org.junit.**
-dontwarn kotlinx.coroutines.**
-dontwarn com.google.errorprone.annotations.**

