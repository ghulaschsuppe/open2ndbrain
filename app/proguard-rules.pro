# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class de.open2ndbrain.** {
    *** Companion;
}
-keepclasseswithmembers class de.open2ndbrain.** {
    kotlinx.serialization.KSerializer serializer(...);
}
