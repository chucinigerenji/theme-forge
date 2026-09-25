package com.dsh.themeforge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 极简文件提供者：把 App 私有目录里的 .mtz 以 content:// 暴露给 ThemeKit / 分享目标，
 * 不依赖 AndroidX。
 */
public class FsProvider extends ContentProvider {

    public static final String AUTH = "com.dsh.themeforge.files";

    public static Uri uriFor(File f) {
        return Uri.parse("content://" + AUTH + f.getAbsolutePath());
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = new File(uri.getPath());
        File root = getContext().getExternalFilesDir(null);
        File root2 = getContext().getFilesDir();
        try {
            String cp = f.getCanonicalPath();
            boolean ok = (root != null && cp.startsWith(root.getCanonicalPath()))
                    || (root2 != null && cp.startsWith(root2.getCanonicalPath()));
            if (!ok) throw new FileNotFoundException("forbidden");
        } catch (java.io.IOException e) {
            throw new FileNotFoundException("bad path");
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
