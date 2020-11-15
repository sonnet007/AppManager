/*
 * Copyright (C) 2020 Muntashir Al-Islam
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.muntashirakon.AppManager.misc;

import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.text.TextUtils;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import androidx.annotation.NonNull;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.types.PrivilegedFile;

// Keep this in sync with https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Environment.java
// Last snapshot https://cs.android.com/android/_/android/platform/frameworks/base/+/bc3d8b9071d4f0b2903d6836770d974e70366290

@SuppressWarnings("unused")
public final class OsEnvironment {
    private static final String TAG = "OsEnvironment";

    private static final String ENV_EXTERNAL_STORAGE = "EXTERNAL_STORAGE";
    private static final String ENV_ANDROID_ROOT = "ANDROID_ROOT";
    private static final String ENV_ANDROID_DATA = "ANDROID_DATA";
    private static final String ENV_ANDROID_EXPAND = "ANDROID_EXPAND";
    private static final String ENV_ANDROID_STORAGE = "ANDROID_STORAGE";
    private static final String ENV_DOWNLOAD_CACHE = "DOWNLOAD_CACHE";
    private static final String ENV_OEM_ROOT = "OEM_ROOT";
    private static final String ENV_ODM_ROOT = "ODM_ROOT";
    private static final String ENV_VENDOR_ROOT = "VENDOR_ROOT";
    private static final String ENV_PRODUCT_ROOT = "PRODUCT_ROOT";
    private static final String ENV_SYSTEM_EXT_ROOT = "SYSTEM_EXT_ROOT";
    private static final String ENV_APEX_ROOT = "APEX_ROOT";

    public static final String DIR_ANDROID = "Android";
    private static final String DIR_DATA = "data";
    private static final String DIR_MEDIA = "media";
    private static final String DIR_OBB = "obb";
    private static final String DIR_FILES = "files";
    private static final String DIR_CACHE = "cache";

    private static final PrivilegedFile DIR_ANDROID_ROOT = getDirectory(ENV_ANDROID_ROOT, "/system");
    private static final PrivilegedFile DIR_ANDROID_DATA = getDirectory(ENV_ANDROID_DATA, "/data");
    private static final PrivilegedFile DIR_ANDROID_EXPAND = getDirectory(ENV_ANDROID_EXPAND, "/mnt/expand");
    private static final PrivilegedFile DIR_ANDROID_STORAGE = getDirectory(ENV_ANDROID_STORAGE, "/storage");
    private static final PrivilegedFile DIR_DOWNLOAD_CACHE = getDirectory(ENV_DOWNLOAD_CACHE, "/cache");
    private static final PrivilegedFile DIR_OEM_ROOT = getDirectory(ENV_OEM_ROOT, "/oem");
    private static final PrivilegedFile DIR_ODM_ROOT = getDirectory(ENV_ODM_ROOT, "/odm");
    private static final PrivilegedFile DIR_VENDOR_ROOT = getDirectory(ENV_VENDOR_ROOT, "/vendor");
    private static final PrivilegedFile DIR_PRODUCT_ROOT = getDirectory(ENV_PRODUCT_ROOT, "/product");
    private static final PrivilegedFile DIR_SYSTEM_EXT_ROOT = getDirectory(ENV_SYSTEM_EXT_ROOT, "/system_ext");
    private static final PrivilegedFile DIR_APEX_ROOT = getDirectory(ENV_APEX_ROOT, "/apex");

    private static UserEnvironment sCurrentUser;
    private static boolean sUserRequired;

    static {
        initForCurrentUser();
    }

    public static void initForCurrentUser() {
        sCurrentUser = new UserEnvironment(Users.getCurrentUserHandle());
    }

    public static class UserEnvironment {
        private final int mUserHandle;

        public UserEnvironment(int userHandle) {
            mUserHandle = userHandle;
        }

        public PrivilegedFile[] getExternalDirs() {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                // The reflection calls cannot be hidden since they are called internally by the
                // system. The alternative is to parse the Context.getExternalCacheDirs(). Some
                // results could be null even though StorageVolume is not null.
                try {
                    @SuppressWarnings("JavaReflectionMemberAccess")
                    Method getVolumeList = StorageManager.class.getMethod("getVolumeList", int.class, int.class);
                    final @NonNull StorageVolume[] volumes = (StorageVolume[]) Objects.requireNonNull(getVolumeList.invoke(null, mUserHandle, 1 << 8 /* FLAG_FOR_WRITE */));
                    Log.e(TAG, Arrays.toString(volumes));
                    final List<PrivilegedFile> files = new ArrayList<>();
                    File tmpFile;
                    for (StorageVolume volume : volumes) {
                        @SuppressWarnings("JavaReflectionMemberAccess")
                        Method getPathFile = StorageVolume.class.getMethod("getPathFile");
                        tmpFile = (File) getPathFile.invoke(volume);
                        if (tmpFile != null) {
                            files.add(new PrivilegedFile(tmpFile.getAbsolutePath()));
                        }
                    }
                    return files.toArray(new PrivilegedFile[0]);
                } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                    e.printStackTrace();
                }
            }
            String rawExternalStorage = System.getenv(ENV_EXTERNAL_STORAGE);
            String rawEmulatedTarget = System.getenv("EMULATED_STORAGE_TARGET");
            List<PrivilegedFile> externalForApp = new ArrayList<>();
            if (!TextUtils.isEmpty(rawEmulatedTarget)) {
                // Device has emulated storage; external storage paths should have
                // userId burned into them.
                final String rawUserId = Integer.toString(mUserHandle);
                final File emulatedTargetBase = new File(rawEmulatedTarget);

                // /storage/emulated/0
                externalForApp.add(buildPath(emulatedTargetBase, rawUserId));
            } else {
                // Device has physical external storage; use plain paths.
                if (TextUtils.isEmpty(rawExternalStorage)) {
                    Log.w(TAG, "EXTERNAL_STORAGE undefined; falling back to default");
                    rawExternalStorage = "/storage/sdcard0";
                }
                // /storage/sdcard0
                externalForApp.add(new PrivilegedFile(rawExternalStorage));
            }
            return externalForApp.toArray(new PrivilegedFile[0]);
        }

        @Deprecated
        public PrivilegedFile getExternalStorageDirectory() {
            return getExternalDirs()[0];
        }

        @Deprecated
        public PrivilegedFile getExternalStoragePublicDirectory(String type) {
            return buildExternalStoragePublicDirs(type)[0];
        }

        public PrivilegedFile[] buildExternalStoragePublicDirs(String type) {
            return buildPaths(getExternalDirs(), type);
        }

        public PrivilegedFile[] buildExternalStorageAndroidDataDirs() {
            return buildPaths(getExternalDirs(), DIR_ANDROID, DIR_DATA);
        }

        public PrivilegedFile[] buildExternalStorageAndroidObbDirs() {
            return buildPaths(getExternalDirs(), DIR_ANDROID, DIR_OBB);
        }

        public PrivilegedFile[] buildExternalStorageAppDataDirs(String packageName) {
            return buildPaths(getExternalDirs(), DIR_ANDROID, DIR_DATA, packageName);
        }

        public PrivilegedFile[] buildExternalStorageAppMediaDirs(String packageName) {
            return buildPaths(getExternalDirs(), DIR_ANDROID, DIR_MEDIA, packageName);
        }

        public PrivilegedFile[] buildExternalStorageAppObbDirs(String packageName) {
            return buildPaths(getExternalDirs(), DIR_ANDROID, DIR_OBB, packageName);
        }

        public PrivilegedFile[] buildExternalStorageAppFilesDirs(String packageName) {
            return buildPaths(getExternalDirs(), DIR_ANDROID, DIR_DATA, packageName, DIR_FILES);
        }

        public PrivilegedFile[] buildExternalStorageAppCacheDirs(String packageName) {
            return buildPaths(getExternalDirs(), DIR_ANDROID, DIR_DATA, packageName, DIR_CACHE);
        }
    }

    /**
     * Return root of the "system" partition holding the core Android OS.
     * Always present and mounted read-only.
     */
    @NonNull
    public static PrivilegedFile getRootDirectory() {
        return DIR_ANDROID_ROOT;
    }

    /**
     * Return root directory of the "oem" partition holding OEM customizations,
     * if any. If present, the partition is mounted read-only.
     */
    @NonNull
    public static PrivilegedFile getOemDirectory() {
        return DIR_OEM_ROOT;
    }

    /**
     * Return root directory of the "odm" partition holding ODM customizations,
     * if any. If present, the partition is mounted read-only.
     */
    @NonNull
    public static PrivilegedFile getOdmDirectory() {
        return DIR_ODM_ROOT;
    }

    /**
     * Return root directory of the "vendor" partition that holds vendor-provided
     * software that should persist across simple reflashing of the "system" partition.
     */
    public static @NonNull PrivilegedFile getVendorDirectory() {
        return DIR_VENDOR_ROOT;
    }

    /**
     * Return root directory of the "product" partition holding product-specific
     * customizations if any. If present, the partition is mounted read-only.
     */
    public static @NonNull PrivilegedFile getProductDirectory() {
        return DIR_PRODUCT_ROOT;
    }

    /**
     * Return root directory of the "system_ext" partition holding system partition's extension
     * If present, the partition is mounted read-only.
     */
    public static @NonNull PrivilegedFile getSystemExtDirectory() {
        return DIR_SYSTEM_EXT_ROOT;
    }

    /**
     * Return the user data directory.
     */
    public static PrivilegedFile getDataDirectory() {
        return DIR_ANDROID_DATA;
    }

    public static PrivilegedFile getDataSystemDirectory() {
        return new PrivilegedFile(getDataDirectory(), "system");
    }

    @NonNull
    public static PrivilegedFile getDataAppDirectory() {
        return new PrivilegedFile(getDataDirectory(), "app");
    }

    @NonNull
    public static PrivilegedFile getDataDataDirectory() {
        return new PrivilegedFile(getDataDirectory(), "data");
    }

    /**
     * Returns the path for android-specific data on the SD card.
     */
    public static PrivilegedFile[] buildExternalStorageAndroidDataDirs() {
        throwIfUserRequired();
        return sCurrentUser.buildExternalStorageAndroidDataDirs();
    }

    /**
     * Generates the raw path to an application's data
     */
    public static PrivilegedFile[] buildExternalStorageAppDataDirs(String packageName) {
        throwIfUserRequired();
        return sCurrentUser.buildExternalStorageAppDataDirs(packageName);
    }

    /**
     * Generates the raw path to an application's media
     */
    public static PrivilegedFile[] buildExternalStorageAppMediaDirs(String packageName) {
        throwIfUserRequired();
        return sCurrentUser.buildExternalStorageAppMediaDirs(packageName);
    }

    /**
     * Generates the raw path to an application's OBB files
     */
    public static PrivilegedFile[] buildExternalStorageAppObbDirs(String packageName) {
        throwIfUserRequired();
        return sCurrentUser.buildExternalStorageAppObbDirs(packageName);
    }

    /**
     * Generates the path to an application's files.
     */
    public static PrivilegedFile[] buildExternalStorageAppFilesDirs(String packageName) {
        throwIfUserRequired();
        return sCurrentUser.buildExternalStorageAppFilesDirs(packageName);
    }

    /**
     * Generates the path to an application's cache.
     */
    public static PrivilegedFile[] buildExternalStorageAppCacheDirs(String packageName) {
        throwIfUserRequired();
        return sCurrentUser.buildExternalStorageAppCacheDirs(packageName);
    }

    public static PrivilegedFile[] buildExternalStoragePublicDirs(@NonNull String dirType) {
        throwIfUserRequired();
        return sCurrentUser.buildExternalStoragePublicDirs(dirType);
    }

    public static PrivilegedFile[] buildExternalStoragePublicDirs() {
        throwIfUserRequired();
        return sCurrentUser.getExternalDirs();
    }

    @NonNull
    static PrivilegedFile getDirectory(String variableName, String defaultPath) {
        String path = System.getenv(variableName);
        return path == null ? new PrivilegedFile(defaultPath) : new PrivilegedFile(path);
    }

    public static void setUserRequired(boolean userRequired) {
        sUserRequired = userRequired;
    }

    private static void throwIfUserRequired() {
        if (sUserRequired) {
            Log.e(TAG, "Path requests must specify a user by using UserEnvironment", new Throwable());
        }
    }

    /**
     * Append path segments to each given base path, returning result.
     */
    @NonNull
    public static PrivilegedFile[] buildPaths(@NonNull File[] base, String... segments) {
        PrivilegedFile[] result = new PrivilegedFile[base.length];
        for (int i = 0; i < base.length; i++) {
            result[i] = buildPath(base[i], segments);
        }
        return result;
    }

    /**
     * Append path segments to given base path, returning result.
     */
    public static PrivilegedFile buildPath(@NonNull File base, @NonNull String... segments) {
        PrivilegedFile cur = new PrivilegedFile(base.getAbsolutePath());
        for (String segment : segments) {
            if (cur == null) {
                cur = new PrivilegedFile(segment);
            } else {
                cur = new PrivilegedFile(cur, segment);
            }
        }
        return cur;
    }
}
