package com.probiotics.tianming;

import android.os.ParcelFileDescriptor;

interface IPrivilegedService {
    int getUid();
    boolean isInUse();
    boolean isInstalled();
    boolean isEnabled();
    boolean setEnable(boolean enable, boolean oneShot);
    boolean boot();
    boolean remove();
    boolean abort();
    boolean startInstallation(String slot);
    int createPartition(String name, long size, boolean readOnly);
    boolean setAshmem(in ParcelFileDescriptor fd, long size);
    boolean submitFromAshmem(long bytes);
    boolean closePartition();
    boolean finishInstallation();
    String listDsuImages();
    boolean replaceDsuImage(String imagePath, String sourcePath);
}
