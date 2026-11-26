# Preserve line numbers for stack traces without bloating class/method names
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Suppress harmless warnings for excluded/desktop-only optional classes
-dontwarn org.bouncycastle.pqc.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn org.apache.commons.logging.**
-dontwarn org.slf4j.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.jsoup.**

# Keep Firebase / Firestore models & Room entities
-keepclassmembers class com.example.** {
    @com.google.firebase.firestore.* <fields>;
    @com.google.firebase.firestore.* <methods>;
}
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Keep PDFBox & standard BouncyCastle providers used for CAS PDF decryption
-keep class com.tom_roush.pdfbox.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.crypto.** { *; }

# Keep Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
