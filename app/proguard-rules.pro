# kotlinx.serialization: keep generated serializers for the app's @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.stocksdetective.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.stocksdetective.**$$serializer { *; }
