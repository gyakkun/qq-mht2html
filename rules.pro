-dontwarn ch.qos.**
-keep,allowshrinking class ch.qos.** { *; }
-keep class org.slf4j.** { *; }
-keep class kotlinx.coroutines.** {*;}
-keep class org.jetbrains.skia.** {*;}
-keep class org.jetbrains.skiko.** {*;}
-keep class androidx.compose.runtime.** { *; }
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-keep public class MainKt {
    public void main();
}