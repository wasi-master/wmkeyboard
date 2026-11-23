package com.google.mlkit.vision.digitalink.recognition.internal

/**
 * Restores the exception class `libdigitalink.so` looks up via JNI `FindClass`
 * (`<init>(ILjava/lang/String;)V`). ML Kit 19.0.0's own `classes.jar` renamed
 * it to `zzm` without keeping the original name, turning every native status
 * error into a `NoClassDefFoundError` that drops the C++ diagnostic string.
 */
class NativeDigitalInkRecognitionException(
    code: Int,
    rawMessage: String?,
) : Exception("code=$code: ${rawMessage.orEmpty()}")
