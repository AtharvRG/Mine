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
