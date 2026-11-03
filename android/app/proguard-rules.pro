# Keep kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class dev.voxwin.app.** {
    *** Companion;
}
-keepclasseswithmembers class dev.voxwin.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
