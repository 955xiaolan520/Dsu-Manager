package com.probiotics.xiaoni;

import android.os.ParcelFileDescriptor;
import com.probiotics.xiaoni.IRootInstallCallback;
import com.probiotics.xiaoni.GsiProgress;
import java.util.List;

interface IPrivilegedService {
    int getUid();
    void setDynProp();
    void forceStopPackage(String packageName);
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
    GsiProgress getInstallationProgress();
    boolean closePartition();
    boolean finishInstallation();
    String getInstalledGsiImageDir();
    String getActiveDsuSlot();
    List<String> getInstalledDsuSlots();
    List<String> getDsuBackingImages(String prefix);
    String listDsuImages();
    String cleanupDsuBackingImages();
    String replaceDsuBackingImage(String slot, String imageName, in ParcelFileDescriptor fd, long size, boolean force, IRootInstallCallback progress);
    List<String> listFiles(String dirPath);
    boolean copyFile(String srcPath, String destPath);
    boolean deleteFile(String path);
}
