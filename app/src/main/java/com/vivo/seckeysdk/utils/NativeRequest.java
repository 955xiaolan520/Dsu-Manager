package com.vivo.seckeysdk.utils;

public final class NativeRequest {
    public final int operateType;
    public final int encryptType;
    public final int keyVersion;
    public final byte[] data;

    public NativeRequest(int operateType, int encryptType, int keyVersion, byte[] data) {
        this.operateType = operateType;
        this.encryptType = encryptType;
        this.keyVersion = keyVersion;
        this.data = data;
    }

    public byte[] getData() { return data; }
    public int getOperateType() { return operateType; }
    public int getEncryptType() { return encryptType; }
    public int getKeyVersion() { return keyVersion; }
    public byte[] getIV() { return new byte[16]; }
    public byte[] getGaloisMAC() { return new byte[16]; }
    public byte[] getNonce() { return new byte[16]; }
}
