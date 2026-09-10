package com.probiotics.xiaoni;

import android.content.Intent;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;


import com.topjohnwu.superuser.ipc.RootService;
import org.lsposed.hiddenapibypass.HiddenApiBypass;

/** Root-side bridge for the hidden dynamic_system Binder service. */
public final class PrivilegedRootService extends RootService {
    private static final String TAG = "TianmingDsu";
    private final IPrivilegedService.Stub bridge = new IPrivilegedService.Stub() {
        @Override public int getUid() { return Process.myUid(); }
        @Override public boolean isInUse() { return bool(7); }
        @Override public boolean isInstalled() { return bool(8); }
        @Override public boolean isEnabled() { return bool(9); }
        @Override public boolean setEnable(boolean enable, boolean oneShot) {
            return transactBoolean(11, p -> { p.writeInt(enable ? 1 : 0); p.writeInt(oneShot ? 1 : 0); });
        }
        @Override public boolean boot() {
            try {
                java.lang.Process reboot = Runtime.getRuntime().exec(new String[]{
                        "/system/bin/setprop", "sys.powerctl", "reboot,dsu"
                });
                return reboot.waitFor() == 0;
            } catch (Exception e) {
                Log.e(TAG, "Unable to reboot to DSU", e);
                return false;
            }
        }
        @Override public boolean remove() { return bool(10); }
        @Override public boolean abort() { return bool(6); }
        @Override public boolean startInstallation(String slot) { return transactBoolean(1, p -> p.writeString(slot)); }
        @Override public int createPartition(String name, long size, boolean readOnly) {
            return transactInt(2, p -> { p.writeString(name); p.writeLong(size); p.writeInt(readOnly ? 1 : 0); });
        }
        @Override public boolean setAshmem(ParcelFileDescriptor fd, long size) {
            return transactBoolean(12, p -> { p.writeTypedObject(fd, 0); p.writeLong(size); });
        }
        @Override public boolean submitFromAshmem(long bytes) { return transactBoolean(13, p -> p.writeLong(bytes)); }
        @Override public boolean closePartition() { return bool(3); }
        @Override public boolean finishInstallation() { return bool(4); }
        @Override public String getInstalledGsiImageDir() {
            try {
                Object gsi = gsiInterface();
                Object path = invokeHidden("android.gsi.IGsiService", gsi, "getInstalledGsiImageDir");
                if (path instanceof String && !((String) path).isEmpty()) return (String) path;
            } catch (Exception error) {
                Log.w(TAG, "Unable to read installed GSI image directory", error);
            }
            return installedImageDirectory().getAbsolutePath();
        }
        @Override public String getActiveDsuSlot() {
            try {
                Object gsi = gsiInterface();
                Object activeSlot = invokeHidden("android.gsi.IGsiService", gsi, "getActiveDsuSlot");
                if (activeSlot instanceof String && !((String) activeSlot).isEmpty())
                    return (String) activeSlot;
                Object slots = invokeHidden("android.gsi.IGsiService", gsi, "getInstalledDsuSlots");
                if (slots instanceof java.util.List && !((java.util.List<?>) slots).isEmpty()) {
                    Object slot = ((java.util.List<?>) slots).get(0);
                    if (slot instanceof String) return (String) slot;
                }
            } catch (Exception error) {
                Log.w(TAG, "Unable to read active DSU slot", error);
            }
            return "";
        }
        @Override public java.util.List<String> getInstalledDsuSlots() {
            try {
                Object slots = invokeHidden("android.gsi.IGsiService", gsiInterface(), "getInstalledDsuSlots");
                if (slots instanceof java.util.List) return (java.util.List<String>) slots;
            } catch (Exception error) {
                Log.w(TAG, "Unable to read installed DSU slots", error);
            }
            return new ArrayList<>();
        }
        @Override public java.util.List<String> getDsuBackingImages(String prefix) {
            validateSlot(prefix);
            try {
                Object imageService = imageServiceInterface(prefix);
                Object images = invokeHidden("android.gsi.IImageService", imageService, "getAllBackingImages");
                if (images instanceof java.util.List) return (java.util.List<String>) images;
            } catch (Exception error) {
                Log.w(TAG, "Unable to read DSU backing images", error);
            }
            return new ArrayList<>();
        }
        @Override public String listDsuImages() {
            StringBuilder result = new StringBuilder();
            Map<String, ImageEntry> imagesByLogicalName = new LinkedHashMap<>();
            java.util.LinkedHashSet<String> slots = new java.util.LinkedHashSet<>();
            String activeSlot = getActiveDsuSlot();
            if (!activeSlot.isEmpty()) slots.add(activeSlot);
            slots.addAll(getInstalledDsuSlots());
            slots.add("dsu");
            for (String slot : slots) {
                if (slot == null || slot.isEmpty()) continue;
                try {
                    Object imageService = imageServiceInterface(slot);
                    Object images = invokeHidden("android.gsi.IImageService", imageService, "getAllBackingImages");
                    if (images instanceof java.util.List) {
                        for (Object value : (java.util.List<?>) images) {
                            if (!(value instanceof String)) continue;
                            addImageEntry(imagesByLogicalName, (String) value,
                                    findInstalledImage((String) value), slot, 2);
                        }
                    }
                } catch (Exception error) {
                    Log.w(TAG, "Unable to read DSU images from slot " + slot, error);
                }
            }
            for (File directory : installedImageDirectories()) {
                File[] files = directory.listFiles();
                if (files == null) continue;
                for (File file : files) {
                    if (file.isFile() && file.getName().matches("[a-z0-9_-]+\\.img\\.[0-9]+"))
                        addImageEntry(imagesByLogicalName, file.getName(), file,
                                imageServicePrefix(directory), 3);
                }
            }
            for (ImageEntry entry : imagesByLogicalName.values()) {
                Object imageService = null;
                try { imageService = imageServiceInterface(entry.slot); } catch (Exception ignored) { }
                appendImage(result, entry.logicalName, entry.targetName, entry.file, imageService);
                result.append('|').append(entry.slot);
            }
            if (result.length() == 0) return "EMPTY|未找到已安装 DSU 镜像";
            return result.toString();
        }
        @Override public String cleanupDsuBackingImages() {
            StringBuilder failures = new StringBuilder();
            try {
                Object imageService = imageServiceInterface("dsu");
                Object images = invokeHidden("android.gsi.IImageService", imageService, "getAllBackingImages");
                if (images instanceof java.util.List) {
                    for (Object value : (java.util.List<?>) images) {
                        if (!(value instanceof String)) continue;
                        String name = (String) value;
                        validateImageName(name);
                        if (imageMapped(imageService, name)) imageVoid(imageService, "unmapImageDevice", name);
                        if (imageExists(imageService, name)) imageVoid(imageService, "deleteBackingImage", name);
                    }
                }
            } catch (Exception error) {
                failures.append(error.getMessage() == null ? error.toString() : error.getMessage());
                Log.w(TAG, "ImageService cleanup failed; using directory cleanup", error);
            }
            for (File directory : installedImageDirectories()) {
                File[] files = directory.listFiles();
                if (files == null) continue;
                for (File file : files) {
                    if (!file.isFile() || !isBackingImageName(file.getName())) continue;
                    try {
                        if (file.exists() && !file.delete() && file.exists()) {
                            if (failures.length() > 0) failures.append("; ");
                            failures.append("无法删除 ").append(file.getName());
                        }
                    } catch (Exception error) {
                        if (failures.length() > 0) failures.append("; ");
                        failures.append("无法删除 ").append(file.getName());
                    }
                }
            }
            return failures.toString();
        }
        private void addImageEntry(Map<String, ImageEntry> imagesByLogicalName, String targetName,
                File file, String slot, int sourcePriority) {
            String serviceName = backingImageServiceName(targetName);
            if (serviceName == null) return;
            String logicalName = displayImageName(serviceName);
            ImageEntry existing = imagesByLogicalName.get(serviceName);
            if (existing == null) {
                imagesByLogicalName.put(serviceName,
                        new ImageEntry(logicalName, serviceName, file, slot, sourcePriority));
                return;
            }
            String selectedTarget = existing.sourcePriority >= sourcePriority
                    ? existing.targetName : serviceName;
            int selectedTargetPriority = Math.max(existing.sourcePriority, sourcePriority);
            File selectedFile = moreUsefulFile(file, existing.file) ? file : existing.file;
            imagesByLogicalName.put(serviceName,
                    new ImageEntry(logicalName, selectedTarget, selectedFile, slot, selectedTargetPriority));
        }
        private boolean moreUsefulFile(File candidate, File current) {
            if (candidate == null || !candidate.isFile()) return false;
            if (current == null || !current.isFile()) return true;
            return candidate.length() > current.length();
        }
        private final class ImageEntry {
            final String logicalName;
            final String targetName;
            final File file;
            final String slot;
            final int sourcePriority;
            ImageEntry(String logicalName, String targetName, File file, String slot, int sourcePriority) {
                this.logicalName = logicalName;
                this.targetName = targetName;
                this.file = file;
                this.slot = slot;
                this.sourcePriority = sourcePriority;
            }
        }
        private void appendImage(StringBuilder result, String displayName, String targetName, File image,
                Object imageService) {
            long size = fileSize(image);
            if (imageService != null) {
                try {
                    long mappedSize = mappedImageSize(imageService, targetName);
                    if (mappedSize > 0) size = mappedSize;
                } catch (Exception error) {
                    Log.w(TAG, "Unable to read mapped size for " + targetName, error);
                }
            }
            if (result.length() > 0) result.append('\n');
            result.append(displayName).append('|').append(size).append('|')
                    .append(image.getAbsolutePath()).append('|').append(sizeLabel(size))
                    .append('|').append(targetName);
        }
        private boolean isImageFile(File file) {
            return isBackingImageName(file.getName());
        }
        private boolean isBackingImageName(String name) {
            if (name == null || name.isEmpty() || name.startsWith(".")) return false;
            String lower = name.toLowerCase(java.util.Locale.US);
            return lower.endsWith(".raw")
                    || lower.matches("[a-z0-9_-]+\\.img(?:\\.[0-9]+)?");
        }
        private boolean isLogicalImageName(String name) {
            if (name == null || name.isEmpty() || name.startsWith(".")) return false;
            String lower = name.toLowerCase(java.util.Locale.US);
            return lower.endsWith(".img") || lower.endsWith(".raw");
        }
        private String publicImageName(String name) {
            if (name == null || name.isEmpty()) return null;
            int marker = name.toLowerCase(java.util.Locale.US).indexOf(".img.");
            return marker >= 0 ? name.substring(0, marker + 4) : name;
        }
        private String logicalImageName(String name) {
            return backingImageServiceName(name);
        }
        private String displayImageName(String targetName) {
            String lower = targetName.toLowerCase(java.util.Locale.US);
            String partitionName = lower.endsWith("_gsi")
                    ? targetName.substring(0, targetName.length() - 4)
                    : targetName;
            return partitionName + ".img";
        }
        private String backingImageFileName(String targetName) {
            return targetName + ".img";
        }
        private String backingImageServiceName(String name) {
            String logicalName = publicImageName(name);
            if (logicalName == null || logicalName.isEmpty() || logicalName.startsWith(".")) return null;
            String lower = logicalName.toLowerCase(java.util.Locale.US);
            if (lower.endsWith(".img") || lower.endsWith(".raw")) {
                int extension = lower.lastIndexOf('.');
                return extension > 0 ? logicalName.substring(0, extension) : null;
            }
            return lower.matches("[a-z0-9_-]+") ? logicalName : null;
        }
        private File installedImageDirectory() {
            return new File("/data/gsi/dsu/dsu");
        }
        private Object gsiInterface() throws Exception {
            Class<?> stub = Class.forName("android.gsi.IGsiService$Stub");
            IBinder binder = service("gsiservice");
            if (binder == null) {
                startGsid();
                binder = waitForService("gsiservice", 10000L);
            }
            if (binder == null) throw new IllegalStateException("gsiservice unavailable");
            return HiddenApiBypass.invoke(stub, null, "asInterface", binder);
        }
        private Object imageServiceInterface(String prefix) throws Exception {
            Object gsi = gsiInterface();
            return invokeHidden("android.gsi.IGsiService", gsi, "openImageService", prefix);
        }
        private Object invokeHidden(String className, Object instance, String method, Object... args) throws Exception {
            try {
                return HiddenApiBypass.invoke(Class.forName(className), instance, method, args);
            } catch (Exception error) {
                Throwable cause = error;
                while (cause.getCause() != null && cause.getCause() != cause)
                    cause = cause.getCause();
                if (cause instanceof Exception && cause != error) throw (Exception) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw error;
            }
        }
        private File[] installedImageDirectories() {
            return new File[]{
                    new File("/data/gsi/dsu/dsu"),
                    new File("/data/gsi/dsu"),
                    new File("/metadata/gsi/dsu/dsu"),
                    new File("/metadata/gsi/dsu")
            };
        }
        private String imageServicePrefix(File directory) {
            String path = directory.getAbsolutePath();
            for (String root : new String[]{"/data/gsi/", "/metadata/gsi/"}) {
                if (path.startsWith(root)) return path.substring(root.length());
            }
            return "dsu/dsu";
        }
        private File findInstalledImage(String name) {
            for (File directory : installedImageDirectories()) {
                String logicalName = logicalImageName(name);
                if (logicalName != null) {
                    File image = new File(directory, backingImageFileName(logicalName) + ".0000");
                    if (image.isFile()) return image;
                    File[] files = directory.listFiles();
                    if (files != null) for (File candidate : files) {
                        String candidateName = logicalImageName(candidate.getName());
                        if (candidate.isFile()
                                && candidate.getName().matches("[a-z0-9_-]+\\.img\\.[0-9]+")
                                && logicalName.equals(candidateName)) return candidate;
                    }
                } else {
                    File image = new File(directory, name);
                    if (image.isFile()) return image;
                    image = new File(directory, name + ".0000");
                    if (image.isFile()) return image;
                }
            }
            return new File(installedImageDirectory(), name);
        }
        private long fileSize(File file) {
            long size = file.length();
            if (size > 0) return size;
            try {
                java.lang.Process process = Runtime.getRuntime().exec(new String[]{"/system/bin/stat", "-c", "%s", file.getAbsolutePath()});
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
                String value = reader.readLine();
                int code = process.waitFor();
                if (code == 0 && value != null) return Long.parseLong(value.trim());
            } catch (Exception ignored) { }
            try {
                java.lang.Process process = Runtime.getRuntime().exec(new String[]{"/system/bin/ls", "-l", file.getAbsolutePath()});
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
                String line = reader.readLine();
                int code = process.waitFor();
                if (code == 0 && line != null) {
                    String[] fields = line.trim().split("\\s+");
                    if (fields.length >= 5) return Long.parseLong(fields[4]);
                }
            } catch (Exception ignored) { }
            return -1;
        }
        private String sizeLabel(long bytes) {
            if (bytes < 0) return "读取失败（权限或文件系统不支持）";
            if (bytes == 0) return "大小未知（可能为稀疏文件或块设备）";
            if (bytes >= 1073741824L) return String.format(java.util.Locale.US, "%.2f GB", bytes / 1073741824d);
            return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576d);
        }
        @Override public String replaceDsuBackingImage(String slot, String imageName,
                ParcelFileDescriptor fd, long size, boolean force) {
            if (fd == null) return "input image fd is null";
            try {
                validateSlot(slot);
                validateImageName(imageName);
                if (size <= 0) return "input image is empty";
                if ((size & 511L) != 0)
                    return "input image size must be 512-byte aligned: " + size;
                Exception firstError = null;
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        Object imageService = imageServiceInterface(slot);
                        writeDsuBackingImage(imageService, imageName, fd, size, force);
                        return "";
                    } catch (Exception error) {
                        firstError = error;
                        if (!isDeadObject(error) || attempt != 0) throw error;
                        Log.w(TAG, "ImageService died while replacing " + imageName
                                + "; reconnecting and retrying", error);
                        startGsid();
                        try { Thread.sleep(500L); }
                        catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw interrupted;
                        }
                    }
                }
                throw firstError;
            } catch (Exception error) {
                Log.e(TAG, "Unable to replace DSU backing image", error);
                return error.getClass().getSimpleName() + ": "
                        + (error.getMessage() == null ? error.toString() : error.getMessage());
            } finally {
                try { fd.close(); } catch (Exception ignored) { }
            }
        }

        private boolean isDeadObject(Throwable error) {
            Throwable cause = error;
            while (cause != null) {
                if ("android.os.DeadObjectException".equals(cause.getClass().getName())) return true;
                cause = cause.getCause();
            }
            return false;
        }

        private void validateSlot(String slot) {
            if (slot == null || slot.length() == 0 || slot.startsWith("/") || slot.contains(".."))
                throw new IllegalArgumentException("invalid GSI slot");
        }

        private void validateImageName(String name) {
            if (name == null || name.length() == 0 || name.contains("/") || name.contains(".."))
                throw new IllegalArgumentException("invalid image name");
        }

        private IBinder service(String name) throws Exception {
            Class<?> manager = Class.forName("android.os.ServiceManager");
            return (IBinder) manager.getMethod("getService", String.class).invoke(null, name);
        }

        private void startGsid() {
            try {
                Runtime.getRuntime().exec(new String[]{"/system/bin/start", "gsid"}).waitFor();
            } catch (Exception error) {
                Log.w(TAG, "Unable to start gsid", error);
            }
        }

        private IBinder waitForService(String name, long timeoutMs) throws Exception {
            long deadline = android.os.SystemClock.uptimeMillis() + timeoutMs;
            IBinder binder;
            while ((binder = service(name)) == null && android.os.SystemClock.uptimeMillis() < deadline) {
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return binder;
        }

        private boolean imageExists(Object service, String name) throws Exception {
            return (Boolean) invokeHidden("android.gsi.IImageService", service,
                    "backingImageExists", name);
        }

        private boolean imageMapped(Object service, String name) throws Exception {
            return (Boolean) invokeHidden("android.gsi.IImageService", service,
                    "isImageMapped", name);
        }

        private String resolveBackingImageName(Object imageService, String sourcePartition) throws Exception {
            String[] exactNames = new String[]{
                    sourcePartition,
                    sourcePartition.endsWith(".img") || sourcePartition.endsWith(".raw")
                            ? sourcePartition : sourcePartition + ".img"
            };
            for (String exactName : exactNames) {
                if (imageExists(imageService, exactName)) return exactName;
            }
            String expectedPartition = partitionBaseName(sourcePartition);
            Object images = invokeHidden("android.gsi.IImageService", imageService, "getAllBackingImages");
            StringBuilder registered = new StringBuilder();
            if (images instanceof java.util.List) {
                for (Object image : (java.util.List<?>) images) {
                    if (!(image instanceof String)) continue;
                    String actualName = (String) image;
                    if (registered.length() > 0) registered.append(',');
                    registered.append(actualName);
                    if (expectedPartition.equals(partitionBaseName(actualName))) {
                        Log.i(TAG, "Resolved source partition " + sourcePartition
                                + " to registered ImageService name " + actualName);
                        return actualName;
                    }
                }
            }
            throw new IOException("no DSU backing image matches source partition: "
                    + sourcePartition + "; registered=" + registered);
        }
        private String partitionBaseName(String name) {
            String base = publicImageName(name);
            if (base == null) return "";
            String lower = base.toLowerCase(java.util.Locale.US);
            if (lower.endsWith(".img") || lower.endsWith(".raw"))
                base = base.substring(0, base.lastIndexOf('.'));
            if (base.toLowerCase(java.util.Locale.US).endsWith("_gsi"))
                base = base.substring(0, base.length() - 4);
            return base.toLowerCase(java.util.Locale.US);
        }

        private void replaceBackingImageFile(String targetName, ParcelFileDescriptor fd, long size)
                throws IOException {
            File target = findInstalledImage(targetName);
            if (!target.isFile())
                throw new IOException("existing DSU backing image is unavailable: " + targetName);
            if (!target.getName().matches("[a-z0-9_-]+\\.img\\.[0-9]+"))
                throw new IOException("target is not a DSU backing data file: " + target.getName());
            Log.i(TAG, "Overwriting existing DSU backing file " + target.getAbsolutePath());
            copyFileToPath(fd, target, size);
        }

        private void replaceMappedBackingImage(Object imageService, String targetName,
                ParcelFileDescriptor fd, long size) throws Exception {
            if (!imageExists(imageService, targetName))
                throw new IOException("DSU backing image does not exist: " + targetName);
            boolean wasMapped = imageMapped(imageService, targetName);
            String mappedPath = null;
            boolean mappedForReplacement = false;
            try {
                if (wasMapped) {
                    mappedPath = mappedImageDevice(imageService, targetName);
                    Log.i(TAG, "Reusing mapped DSU block device " + mappedPath
                            + " for " + targetName);
                } else {
                    Log.i(TAG, "Mapping existing DSU backing image " + targetName);
                    mappedPath = imageMap(imageService, targetName);
                    mappedForReplacement = true;
                }
                if (mappedPath == null || mappedPath.length() == 0)
                    throw new IOException("mapped DSU block device path is empty: " + targetName);
                Log.i(TAG, "Writing mapped DSU block device " + mappedPath
                        + " for " + targetName + " bytes=" + size);
                copyFileToBlockDevice(fd, mappedPath, size);
            } finally {
                try {
                    if (mappedForReplacement && imageMapped(imageService, targetName))
                        imageVoid(imageService, "unmapImageDevice", targetName);
                } catch (Exception error) {
                    Log.w(TAG, "Unable to unmap DSU image " + targetName, error);
                }
            }
        }

        private Set<String> backingImageNames(Object service) throws Exception {
            Set<String> names = new HashSet<>();
            Object images = invokeHidden("android.gsi.IImageService", service, "getAllBackingImages");
            if (images instanceof java.util.List) for (Object image : (java.util.List<?>) images) {
                if (image instanceof String) names.add((String) image);
            }
            return names;
        }

        private void imageVoid(Object service, String method, String name) throws Exception {
            invokeHidden("android.gsi.IImageService", service, method, name);
        }

        private String imageMap(Object service, String name) throws Exception {
            Class<?> mappedClass = Class.forName("android.gsi.MappedImage");
            Object mapped = mappedClass.newInstance();
            invokeHidden("android.gsi.IImageService", service, "mapImageDevice", name, 10000, mapped);
            java.lang.reflect.Field path = mappedClass.getDeclaredField("path");
            path.setAccessible(true);
            return (String) path.get(mapped);
        }
        private String mappedImageDevice(Object service, String name) throws Exception {
            return (String) invokeHidden("android.gsi.IImageService", service,
                    "getMappedImageDevice", name);
        }

        private long mappedImageSize(Object service, String name) throws Exception {
            boolean alreadyMapped = imageMapped(service, name);
            String path = alreadyMapped
                    ? mappedImageDevice(service, name)
                    : imageMap(service, name);
            try {
                if (path == null || path.length() == 0)
                    throw new IOException("mapped DSU block device path is empty: " + name);
                long size = blockDeviceSize(path);
                if (size <= 0) throw new IOException("unable to read mapped size for " + name);
                return size;
            } finally {
                if (!alreadyMapped) imageVoid(service, "unmapImageDevice", name);
            }
        }

        private long blockDeviceSize(String path) throws IOException {
            java.lang.Process process = Runtime.getRuntime().exec(new String[]{
                    "/system/bin/blockdev", "--getsize64", path
            });
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String value = reader.readLine();
                int code = process.waitFor();
                if (code == 0 && value != null) return Long.parseLong(value.trim());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while reading block device size", error);
            }
            throw new IOException("blockdev failed for " + path);
        }

        private void writeDsuBackingImage(Object service, String name, ParcelFileDescriptor fd,
                long size, boolean force) throws Exception {
            boolean existed = imageExists(service, name);
            boolean mapped = imageMapped(service, name);
            if (existed && !force) throw new IllegalStateException("Image already exists: " + name);
            if (mapped) imageVoid(service, "unmapImageDevice", name);
            if (existed) imageVoid(service, "deleteBackingImage", name);
            boolean created = false;
            boolean mappedNow = false;
            try {
                imageCreate(service, name, size);
                created = true;
                String mappedPath = imageMap(service, name);
                if (mappedPath == null || mappedPath.length() == 0)
                    throw new IllegalStateException("mapImageDevice(" + name + ") returned empty path");
                mappedNow = true;
                copyFileToBlockDevice(fd, mappedPath, size);
                imageVoid(service, "unmapImageDevice", name);
                mappedNow = false;
            } catch (Exception error) {
                if (mappedNow) try { imageVoid(service, "unmapImageDevice", name); } catch (Exception ignored) { }
                if (created) try { imageVoid(service, "deleteBackingImage", name); } catch (Exception ignored) { }
                throw error;
            }
        }

        private void imageCreate(Object service, String name, long size) throws Exception {
            invokeHidden("android.gsi.IImageService", service, "createBackingImage",
                    name, size, 1, null);
        }

        private void copyFileToBlockDevice(ParcelFileDescriptor fd, String path, long expected) throws IOException {
            copyFileToPath(fd, new File(path), expected);
        }

        private void copyFileToPath(ParcelFileDescriptor fd, File target, long expected) throws IOException {
            long copied = 0;
            try (ParcelFileDescriptor duplicate = ParcelFileDescriptor.dup(fd.getFileDescriptor());
                 ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(duplicate);
                 FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[4 * 1024 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    copied += count;
                }
                output.getFD().sync();
            }
            if (copied != expected) throw new IOException("short image copy");
        }
    };

    @Override public IBinder onBind(Intent intent) { return bridge; }

    private IBinder dynamicSystem() {
        try {
            Class<?> manager = Class.forName("android.os.ServiceManager");
            return (IBinder) manager.getMethod("getService", String.class)
                    .invoke(null, "dynamic_system");
        } catch (Exception e) {
            Log.e(TAG, "Unable to resolve dynamic_system", e);
            return null;
        }
    }
    private interface Writer { void write(Parcel parcel); }

    private boolean bool(int code) { return transactBoolean(code, null); }

    private boolean transactBoolean(int code, Writer writer) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("android.os.image.IDynamicSystemService");
            if (writer != null) writer.write(data);
            IBinder service = dynamicSystem();
            if (service == null || !service.transact(code, data, reply, 0)) return false;
            reply.readException();
            return reply.readInt() != 0;
        } catch (Exception e) {
            Log.e(TAG, "dynamic_system transaction failed: " + code, e);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private int transactInt(int code, Writer writer) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("android.os.image.IDynamicSystemService");
            writer.write(data);
            IBinder service = dynamicSystem();
            if (service == null || !service.transact(code, data, reply, 0)) return -1;
            reply.readException();
            return reply.readInt();
        } catch (Exception e) {
            Log.e(TAG, "dynamic_system transaction failed: " + code, e);
            return -1;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
