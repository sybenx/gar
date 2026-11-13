-keep class com.sun.jna.* { *; }
-keepclassmembers class * extends com.sun.jna.* { public *; }

# GAR: the LLM router builds generated sentence data by reflection (gar/call/GarSentenceBinder.kt)
-keep class org.stypox.dicio.sentences.** { *; }

# GAR: JNA references AWT, which Android lacks (R8 in the benchmark build)
-dontwarn java.awt.**

# GAR: protobuf-lite finds message fields by name (DataStore settings); R8 must not rename them
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
