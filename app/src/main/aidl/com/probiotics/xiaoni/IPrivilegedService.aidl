package com.probiotics.xiaoni;

import android.os.ParcelFileDescriptor;
import java.util.List;

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
    String getInstalledGsiImageDir();
    String getActiveDsuSlot();
    List<String> getInstalledDsuSlots();
    List<String> getDsuBackingImages(String prefix);
    String listDsuImages();
    String cleanupDsuBackingImages();
    String replaceDsuBackingImage(String slot, String imageName, in ParcelFileDescriptor fd, long size, boolean force);
}
