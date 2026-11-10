-keep class com.sun.jna.* { *; }
-keepclassmembers class * extends com.sun.jna.* { public *; }

# GAR: the LLM router builds generated sentence data by reflection (gar/call/GarSentenceBinder.kt)
-keep class org.stypox.dicio.sentences.** { *; }
