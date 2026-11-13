# Keep JSch intact: it resolves crypto providers and key implementations reflectively.
-keep class com.jcraft.jsch.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
