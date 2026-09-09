package com.probiotics.xiaoni;

import android.content.Context;
import android.content.ContextWrapper;
import com.vivo.seckeysdk.utils.NativeRequest;
import com.vivo.seckeysdk.utils.NativeResponse;
import com.vivo.seckeysdk.utils.SDKCipherNative;
import com.vivo.seckeysdk.utils.SDKCipherConfig;

final class VivoCrypto {
    private VivoCrypto() { }

    static synchronized boolean init(Context context) {
        try {
            SDKCipherConfig.isCheckSign = false;
            SDKCipherConfig.isCheckInstaller = false;
            return SDKCipherNative.init(new ContextWrapper(context) {
                @Override public String getPackageName() { return "com.bbk.updater"; }
            });
        } catch (Throwable error) {
            return false;
        }
    }

    static byte[] crypt(byte[] input, boolean encrypt) {
        NativeResponse response = SDKCipherNative.execute(
                new NativeRequest(encrypt ? 1 : 2, 1, 2, input));
        if (response == null || response.err != 0 || response.output == null) {
            throw new IllegalStateException("Vivo 加密引擎返回错误");
        }
        return response.output;
    }
}
