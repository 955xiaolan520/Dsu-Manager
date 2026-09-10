package com.probiotics.xiaoni;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public final class UpdateFileProvider extends ContentProvider {
    private static final String PATH_PREFIX = "/updates/";

    public static Uri getUriForFile(Context context, File file) {
        return Uri.parse("content://" + context.getPackageName() + ".fileprovider" + PATH_PREFIX + file.getName());
    }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode) || uri.getPath() == null || !uri.getPath().startsWith(PATH_PREFIX)) {
            throw new FileNotFoundException("Read-only update URI required");
        }
        File cacheDir;
        File file;
        try {
            cacheDir = requireContext().getCacheDir().getCanonicalFile();
            file = new File(cacheDir, uri.getPath().substring(PATH_PREFIX.length())).getCanonicalFile();
        } catch (IOException error) {
            throw new FileNotFoundException("Unable to resolve update APK");
        }
        if (!file.getPath().startsWith(cacheDir.getPath() + File.separator) || !file.isFile()) {
            throw new FileNotFoundException("Update APK not found");
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
}
