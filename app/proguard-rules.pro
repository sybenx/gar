-keep class com.sun.jna.* { *; }
-keepclassmembers class * extends com.sun.jna.* { public *; }

# GAR: the LLM router builds generated sentence data by reflection (gar/call/GarSentenceBinder.kt)
-keep class org.stypox.dicio.sentences.** { *; }

# GAR: JNA references AWT, which Android lacks (R8 in the benchmark build)
-dontwarn java.awt.**

# GAR: protobuf-lite finds message fields by name (DataStore settings); R8 must not rename them
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }

# GAR: only the minified benchmark/phone build hits these (release isn't minified).
# dicio-numbers creates its per-language parsers and formatters by reflection (ParserFormatter).
-keep class org.dicio.numbers.** { <init>(...); }
# gar:assist creates the sheet UI by name (GarAssist.SHEET_UI_CLASS).
-keep class org.stypox.dicio.gar.sheet.GarSheetUi { <init>(); }
