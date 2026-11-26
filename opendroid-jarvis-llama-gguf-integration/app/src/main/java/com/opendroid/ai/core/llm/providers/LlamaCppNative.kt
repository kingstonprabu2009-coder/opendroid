package com.opendroid.ai.core.llm.providers

/** Small JNI boundary; native llama.cpp resources are owned by the singleton provider. */
internal class LlamaCppNative {
    external fun nativeLoad(path: String, contextSize: Int, threads: Int): Boolean
    external fun nativeLoadError(): String
    external fun nativeGenerate(prompt: String, maxTokens: Int, temperature: Float): String
    external fun nativeCancel()
    external fun nativeRelease()
}
