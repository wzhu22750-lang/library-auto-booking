package com.zwlib.quick;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.XmlResourceParser;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import org.xmlpull.v1.XmlPullParser;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 独立的轻量 FileProvider 实现，不依赖 androidx，兼容 Android 7.0+ 拍照 content:// Uri 分享。
 */
public class QuickFileProvider extends ContentProvider {

    private static final String[] COLUMNS = {
            OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE
    };

    private static final HashMap<String, File> mRoots = new HashMap<String, File>();
    private static boolean mInitialized = false;

    @Override
    public boolean onCreate() {
        return true;
    }

    private synchronized static void initRoots(Context context) {
        if (mInitialized) return;
        try {
            int resId = context.getResources().getIdentifier("file_paths", "xml", context.getPackageName());
            if (resId != 0) {
                XmlResourceParser parser = context.getResources().getXml(resId);
                int type;
                while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                    if (type == XmlPullParser.START_TAG) {
                        String tag = parser.getName();
                        String name = parser.getAttributeValue(null, "name");
                        String path = parser.getAttributeValue(null, "path");
                        File target = null;
                        if ("cache-path".equals(tag)) {
                            target = buildPath(context.getCacheDir(), path);
                        } else if ("external-cache-path".equals(tag)) {
                            target = buildPath(context.getExternalCacheDir(), path);
                        } else if ("files-path".equals(tag)) {
                            target = buildPath(context.getFilesDir(), path);
                        } else if ("external-path".equals(tag)) {
                            target = buildPath(android.os.Environment.getExternalStorageDirectory(), path);
                        }
                        if (target != null && name != null) {
                            mRoots.put(name, target.getCanonicalFile());
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (context.getCacheDir() != null) {
            try {
                mRoots.put("cache", context.getCacheDir().getCanonicalFile());
            } catch (IOException ignored) {}
        }
        if (context.getExternalCacheDir() != null) {
            try {
                mRoots.put("external_cache", context.getExternalCacheDir().getCanonicalFile());
            } catch (IOException ignored) {}
        }
        mInitialized = true;
    }

    private static File buildPath(File base, String path) {
        if (base == null) return null;
        if (path == null || path.isEmpty() || ".".equals(path)) {
            return base;
        }
        return new File(base, path);
    }

    public static Uri getUriForFile(Context context, String authority, File file) {
        initRoots(context);
        try {
            String filePath = file.getCanonicalPath();
            Map.Entry<String, File> mostSpecific = null;
            for (Map.Entry<String, File> root : mRoots.entrySet()) {
                String rootPath = root.getValue().getPath();
                if (filePath.startsWith(rootPath)) {
                    if (mostSpecific == null || rootPath.length() > mostSpecific.getValue().getPath().length()) {
                        mostSpecific = root;
                    }
                }
            }
            if (mostSpecific == null) {
                throw new IllegalArgumentException("Failed to find configured root that contains " + filePath);
            }
            String rootPath = mostSpecific.getValue().getPath();
            String path;
            if (rootPath.endsWith("/")) {
                path = filePath.substring(rootPath.length());
            } else {
                path = filePath.substring(rootPath.length() + 1);
            }
            return new Uri.Builder()
                    .scheme("content")
                    .authority(authority)
                    .encodedPath(Uri.encode(mostSpecific.getKey()) + '/' + Uri.encode(path, "/"))
                    .build();
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to resolve canonical path for " + file, e);
        }
    }

    private File getFileForUri(Uri uri) throws FileNotFoundException {
        Context context = getContext();
        if (context == null) throw new FileNotFoundException("Context is null");
        initRoots(context);

        String path = uri.getEncodedPath();
        int splitIndex = path.indexOf('/', 1);
        String tag = Uri.decode(path.substring(1, splitIndex > 0 ? splitIndex : path.length()));
        String subPath = splitIndex > 0 ? Uri.decode(path.substring(splitIndex + 1)) : "";

        File root = mRoots.get(tag);
        if (root == null) {
            throw new FileNotFoundException("Unable to find configured root for " + uri);
        }
        File file = new File(root, subPath);
        try {
            file = file.getCanonicalFile();
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to resolve canonical path for " + file);
        }
        if (!file.getPath().startsWith(root.getPath())) {
            throw new SecurityException("Resolved path jumped beyond configured root");
        }
        return file;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = getFileForUri(uri);
        int fileMode = ParcelFileDescriptor.MODE_READ_ONLY;
        if (mode.contains("w")) {
            fileMode |= ParcelFileDescriptor.MODE_WRITE_ONLY;
        }
        if (mode.contains("+")) {
            fileMode |= ParcelFileDescriptor.MODE_READ_WRITE;
        }
        return ParcelFileDescriptor.open(file, fileMode);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File file = getFileForUri(uri);
            if (projection == null) {
                projection = COLUMNS;
            }
            String[] cols = new String[projection.length];
            Object[] values = new Object[projection.length];
            int i = 0;
            for (String col : projection) {
                if (OpenableColumns.DISPLAY_NAME.equals(col)) {
                    cols[i] = OpenableColumns.DISPLAY_NAME;
                    values[i++] = file.getName();
                } else if (OpenableColumns.SIZE.equals(col)) {
                    cols[i] = OpenableColumns.SIZE;
                    values[i++] = file.length();
                }
            }
            cols = copyOf(cols, i);
            values = copyOf(values, i);
            MatrixCursor cursor = new MatrixCursor(cols, 1);
            cursor.addRow(values);
            return cursor;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public String getType(Uri uri) {
        try {
            File file = getFileForUri(uri);
            String name = file.getName();
            int lastDot = name.lastIndexOf('.');
            if (lastDot >= 0) {
                String extension = name.substring(lastDot + 1);
                String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                if (mime != null) return mime;
            }
        } catch (Throwable ignored) {}
        return "application/octet-stream";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("No external inserts");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("No external updates");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        try {
            File file = getFileForUri(uri);
            return file.delete() ? 1 : 0;
        } catch (FileNotFoundException e) {
            return 0;
        }
    }

    private static String[] copyOf(String[] original, int newLength) {
        String[] result = new String[newLength];
        System.arraycopy(original, 0, result, 0, Math.min(original.length, newLength));
        return result;
    }

    private static Object[] copyOf(Object[] original, int newLength) {
        Object[] result = new Object[newLength];
        System.arraycopy(original, 0, result, 0, Math.min(original.length, newLength));
        return result;
    }
}
