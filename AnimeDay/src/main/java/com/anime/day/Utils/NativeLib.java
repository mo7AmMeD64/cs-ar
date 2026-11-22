package com.anime.day.Utils;

import android.content.Context;

/**
 * Mirrors com.anime.day.Utils.NativeLib from the original app.
 * JNI_OnLoad inside libnative-lib.so registers these methods dynamically,
 * so the package, class and method names must match exactly.
 */
public final class NativeLib {
    private NativeLib() {}

    public static native String buildSecure(Context context);

    public static native String decryptResponse(String data, String key);
}
