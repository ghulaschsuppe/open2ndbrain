# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class de.open2ndbrain.** {
    *** Companion;
}
-keepclasseswithmembers class de.open2ndbrain.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# sherpa-onnx: JNI greift per Name auf Klassen/Felder zu
-keep class com.k2fsa.sherpa.onnx.** { *; }
