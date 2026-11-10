# GAR: the JNI functions in gar_llama.cpp are looked up by class and method name
-keep class cc.aaro.gar.llm.LlamaNative {
    native <methods>;
}
