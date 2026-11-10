# GAR: the JNI functions in gar_whisper.cpp are looked up by class and method name
-keep class cc.aaro.gar.stt.WhisperNative {
    native <methods>;
}
