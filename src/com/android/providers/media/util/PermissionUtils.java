/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.media.util;

import static android.Manifest.permission.ACCESS_MEDIA_LOCATION;
import static android.Manifest.permission.ACCESS_MTP;
import static android.Manifest.permission.BACKUP;
import static android.Manifest.permission.INSTALL_PACKAGES;
import static android.Manifest.permission.MANAGE_EXTERNAL_STORAGE;
import static android.Manifest.permission.MANAGE_MEDIA;
import static android.Manifest.permission.QUERY_ALL_PACKAGES;
import static android.Manifest.permission.READ_EXTERNAL_STORAGE;
import static android.Manifest.permission.READ_MEDIA_AUDIO;
import static android.Manifest.permission.READ_MEDIA_IMAGES;
import static android.Manifest.permission.READ_MEDIA_VIDEO;
import static android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED;
import static android.Manifest.permission.UPDATE_DEVICE_STATS;
import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.OPSTR_LEGACY_STORAGE;
import static android.app.AppOpsManager.OPSTR_NO_ISOLATED_STORAGE;
import static android.app.AppOpsManager.OPSTR_READ_MEDIA_AUDIO;
import static android.app.AppOpsManager.OPSTR_READ_MEDIA_IMAGES;
import static android.app.AppOpsManager.OPSTR_READ_MEDIA_VIDEO;
import static android.app.AppOpsManager.OPSTR_REQUEST_INSTALL_PACKAGES;
import static android.app.AppOpsManager.OPSTR_WRITE_MEDIA_AUDIO;
import static android.app.AppOpsManager.OPSTR_WRITE_MEDIA_IMAGES;
import static android.app.AppOpsManager.OPSTR_WRITE_MEDIA_VIDEO;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.provider.CloudMediaProviderContract.MANAGE_CLOUD_MEDIA_PROVIDERS_PERMISSION;

import android.annotation.UserIdInt;
import android.app.AppOpsManager;
import android.app.DownloadManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.modules.utils.build.SdkLevel;

public class PermissionUtils {

    // Callers must hold both the old and new permissions, so that we can
    // handle obscure cases like when an app targets Q but was installed on
    // a device that was originally running on P before being upgraded to Q.

    private static ThreadLocal<String> sOpDescription = new ThreadLocal<>();

    public static void setOpDescription(@Nullable String description) {
        sOpDescription.set(description);
    }

    public static void clearOpDescription() { sOpDescription.set(null); }

    public static boolean checkPermissionSelf(@NonNull Context context, int pid, int uid) {
        return UserHandle.getAppId(android.os.Process.myUid()) == UserHandle.getAppId(uid);
    }

    /**
     * Return {@code true} when the given user id's corresponsing app id is the same as current
     * process's app id, else return {@code false}.
     */
    public static boolean checkPermissionSelf(@UserIdInt int uid) {
        return UserHandle.getAppId(android.os.Process.myUid()) == UserHandle.getAppId(uid);
    }

    /**
     * Returns {@code true} if the given {@code uid} is a {@link android.os.Process.ROOT_UID} or
     * {@link android.os.Process.SHELL_UID}. {@code false} otherwise.
     */
    public static boolean checkPermissionShell(int uid) {
        switch (uid) {
            case android.os.Process.ROOT_UID:
            case android.os.Process.SHELL_UID:
                return true;
            default:
                return false;
        }
    }

    /**
     * @return {@code true} if the given {@code uid} is {@link android.os.Process#SYSTEM_UID},
     *         {@code false} otherwise.
     */
    public static boolean checkPermissionSystem(int uid) {
        return UserHandle.getAppId(uid) == android.os.Process.SYSTEM_UID;
    }

    /**
     * Check if the given package has been granted the "file manager" role on
     * the device, which should grant them certain broader access.
     */
    public static boolean checkPermissionManager(@NonNull Context context, int pid,
            int uid, @NonNull String packageName, @Nullable String attributionTag) {
        return checkPermissionForDataDelivery(context, MANAGE_EXTERNAL_STORAGE, pid, uid,
                packageName, attributionTag,
                generateAppOpMessage(packageName,sOpDescription.get()));
    }

    /**
     * Check if the given package has the ability to "delegate" the ownership of
     * media items that they own to other apps, typically when they've finished
     * performing operations on behalf of those apps.
     * <p>
     * One use-case for this is backup/restore apps, where the app restoring the
     * content needs to shift the ownership back to the app that originally
     * owned that media.
     * <p>
     * Another use-case is {@link DownloadManager}, which shifts ownership of
     * finished downloads to the app that originally requested them.
     */
    public static boolean checkPermissionDelegator(@NonNull Context context, int pid, int uid) {
        return (context.checkPermission(BACKUP, pid, uid) == PERMISSION_GRANTED)
                || (context.checkPermission(UPDATE_DEVICE_STATS, pid, uid) == PERMISSION_GRANTED);
    }

    public static boolean checkPermissionWriteStorage(@NonNull Context context, int pid, int uid,
            @NonNull String packageName, @Nullable String attributionTag) {
        return checkPermissionForDataDelivery(context, WRITE_EXTERNAL_STORAGE, pid, uid,
                packageName, attributionTag,
                generateAppOpMessage(packageName,sOpDescription.get()));
    }

    public static boolean checkPermissionReadStorage(@NonNull Context context, int pid, int uid,
            @NonNull String packageName, @Nullable String attributionTag) {
        return checkPermissionForDataDelivery(context, READ_EXTERNAL_STORAGE, pid, uid,
                packageName, attributionTag,
                generateAppOpMessage(packageName,sOpDescription.get()));
    }

    /**
     * Check for read permission when legacy storage is granted.
     * There is a bug in AppOpsManager that keeps legacy storage granted even
     * when an app updates its targetSdkVersion from value <30 to >=30.
     * If an app upgrades from targetSdk 29 to targetSdk 33, legacy storage
     * remains granted and in targetSdk 33, app are required to replace R_E_S
     * with R_M_*. If an app updates its manifest with R_M_*, permission check
     * in MediaProvider will look for R_E_S and will not grant read access as
     * the app would be still treated as legacy. Ensure that legacy app either has
     * R_E_S or all of R_M_* to get read permission. Since this is a fix for legacy
     * app op bug, we are avoiding granular permission checks based on media type.
     */
    public static boolean checkPermissionReadForLegacyStorage(@NonNull Context context,
            int pid, int uid, @NonNull String packageName, @Nullable String attributionTag,
            boolean isTargetSdkAtleastT) {
        if (isTargetSdkAtleastT) {
            return checkPermissionForDataDelivery(context, READ_EXTERNAL_STORAGE, pid, uid,
                    packageName, attributionTag,
                    generateAppOpMessage(packageName, sOpDescription.get())) || (
                    checkPermissionForDataDelivery(context, READ_MEDIA_IMAGES, pid, uid,
                            packageName, attributionTag,
                            generateAppOpMessage(packageName, sOpDescription.get()))
                            && checkPermissionForDataDelivery(context, READ_MEDIA_VIDEO, pid, uid,
                            packageName, attributionTag,
                            generateAppOpMessage(packageName, sOpDescription.get()))
                            && checkPermissionForDataDelivery(context, READ_MEDIA_AUDIO, pid, uid,
                            packageName, attributionTag,
                            generateAppOpMessage(packageName, sOpDescription.get())));
        } else {
            return checkPermissionForDataDelivery(context, READ_EXTERNAL_STORAGE, pid, uid,
                    packageName, attributionTag,
                    generateAppOpMessage(packageName, sOpDescription.get()));
        }
    }

    /**
     * Check if the given package has been granted the
     * android.Manifest.permission#ACCESS_MEDIA_LOCATION permission.
     */
    public static boolean checkPermissionAccessMediaLocation(@NonNull Context context, int pid,
            int uid, @NonNull String packageName, @Nullable String attributionTag,
            boolean isTargetSdkAtLeastT) {
        return checkPermissionForDataDelivery(context, ACCESS_MEDIA_LOCATION, pid, uid, packageName,
                attributionTag, generateAppOpMessage(packageName, sOpDescription.get()))
                || checkPermissionAccessMediaCompatGrant(context, pid, uid, packageName,
                attributionTag, isTargetSdkAtLeastT);
    }

    /**
     *  Check if ACCESS_MEDIA_LOCATION is requested, and that READ_MEDIA_VISUAL_USER_SELECTED is
     *  implicitly requested and fully granted
     */
    private static boolean checkPermissionAccessMediaCompatGrant(@NonNull Context context, int pid,
            int uid, @NonNull String packageName, @Nullable String attributionTag,
            boolean isTargetSdkAtLeastT) {
        if (!SdkLevel.isAtLeastU() || !isTargetSdkAtLeastT) {
            return false;
        }
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(packageName,
                    PackageManager.GET_PERMISSIONS);
            if (pi.requestedPermissions == null) {
                return false;
            }

            boolean amlRequested = false;
            boolean userSelectedImplicit = false;
            for (int i = 0; i < pi.requestedPermissions.length; i++) {
                if (ACCESS_MEDIA_LOCATION.equals(pi.requestedPermissions[i])) {
                    amlRequested = true;
                }
                if (READ_MEDIA_VISUAL_USER_SELECTED.equals(pi.requestedPermissions[i])) {
                    userSelectedImplicit = (pi.requestedPermissionsFlags[i]
                            & PackageInfo.REQUESTED_PERMISSION_IMPLICIT) != 0;
                }
            }

            return amlRequested && userSelectedImplicit && checkPermissionReadVisualUserSelected(
                    context, pid, uid, packageName, attributionTag, isTargetSdkAtLeastT,
                    /* forDataDelivery */ true);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Check if the given package has been granted the
     * android.Manifest.permission#MANAGE_MEDIA permission.
     */
    public static boolean checkPermissionManageMedia(@NonNull Context context, int pid, int uid,
            @NonNull String packageName, @Nullable String attributionTag) {
        return checkPermissionForDataDelivery(context, MANAGE_MEDIA, pid, uid, packageName,
                attributionTag, generateAppOpMessage(packageName, sOpDescription.get()));
    }

    public static boolean checkIsLegacyStorageGranted(@NonNull Context context, int uid,
            String packageName, boolean isTargetSdkAtLeastV) {
        if (!isTargetSdkAtLeastV && context.getSystemService(AppOpsManager.class)
                .unsafeCheckOp(OPSTR_LEGACY_STORAGE, uid, packageName) == MODE_ALLOWED) {
            return true;
        }
        // Check OPSTR_NO_ISOLATED_STORAGE app op.
        return checkNoIsolatedStorageGranted(context, uid, packageName);
    }

    public static boolean checkPermissionReadAudio(
            @NonNull Context context,
            int pid,
            int uid,
            @NonNull String packageName,
            @Nullable String attributionTag,
            boolean targetSdkIsAtLeastT,
            boolean forDataDelivery) {

        String permission = targetSdkIsAtLeastT && SdkLevel.isAtLeastT()
                ? READ_MEDIA_AUDIO : READ_EXTERNAL_STORAGE;

        if (!checkPermissionForPreflight(context, permission, pid, uid, packageName)) {
            return false;
        }
        return checkAppOpAllowingLegacy(context, OPSTR_READ_MEDIA_AUDIO, pid,
                uid, packageName, attributionTag,
                generateAppOpMessage(packageName, sOpDescription.get()), forDataDelivery);
    }

    public static boolean checkPermissionWriteAudio(@NonNull Context context, int pid, int uid,
            @NonNull String packageName, @Nullable String attributionTag, boolean forDataDelivery) {
        if (!checkPermissionAllowingNonLegacy(
                    context, WRITE_EXTERNAL_STORAGE, pid, uid, packageName)) {
            return false;
        }
        return checkAppOpAllowingLegacy(context, OPSTR_WRITE_MEDIA_AUDIO, pid,
                uid, packageName, attributionTag,
                generateAppOpMessage(packageName, sOpDescription.get()), forDataDelivery);
    }

    public static boolean checkPermissionReadVideo(
            @NonNull Context context,
            int pid,
            int uid,
            @NonNull String packageName,
            @Nullable String attributionTag,
            boolean targetSdkIsAtLeastT,
            boolean forDataDelivery) {
        String permission = targetSdkIsAtLeastT && SdkLevel.isAtLeastT()
                ? READ_MEDIA_VIDEO : READ_EXTERNAL_STORAGE;

        if (!checkPermissionForPreflight(context, permission, pid, uid, packageName)) {
            return false;
        }

        return checkAppOpAllowingLegacy(context, OPSTR_READ_MEDIA_VIDEO, pid,
                uid, packageName, attributionTag,
                generateAppOpMessage(packageName, sOpDescription.get()), forDataDelivery);
    }

    public static boolean checkPermissionWriteVideo(@NonNull Context context, int pid, int uid,
            @NonNull String packageName, @Nullable String attributionTag, boolean forDataDelivery) {
        if (!checkPermissionAllowingNonLegacy(
                context, WRITE_EXTERNAL_STORAGE, pid, uid, packageName)) {
            return false;
        }
        return checkAppOpAllowingLegacy(context, OPSTR_WRITE_MEDIA_VIDEO, pid,
                uid, packageName, attributionTag,
                generateAppOpMessage(packageName, sOpDescription.get()), forDataDelivery);
    }

    public static boolean checkPermissionReadImages(
            @NonNull Context context,
            int pid,
            int uid,
            @NonNull String packageName,
            @Nullable String attributionTag,
            boolean targetSdkIsAtLeastT, boolean forDataDelivery) {
        String permission = targetSdkIsAtLeastT && SdkLevel.isAtLeastT()
                ? READ_MEDIA_IMAGES : READ_EXTERNAL_STORAGE;

        if (!checkPermissionForPreflight(context, permission, pid, uid, packageName)) {
            return false;
        }

        return checkAppOpAllowingLegacy(context, OPSTR_READ_MEDIA_IMAGES, pid,
                uid, packageName, attributionTag,
                generateAppOpMessage(packageName, sOpDescription.get()), forDataDelivery);
    }

    public static boolean checkPermissionWriteImages(@NonNull Context context, int pid, int uid,
            @NonNull String packageName, @Nullable String attributionTag, boolean forDataDelivery) {
        if (!checkPermissionAllowingNonLegacy(
                context, WRITE_EXTERNAL_STORAGE, pid, uid, packageName)) {
            return false;
        }
        return checkAppOpAllowingLegacy(context, OPSTR_WRITE_MEDIA_IMAGES, pid,
                uid, packageName, attributionTag,
                generateAppOpMessage(packageName, sOpDescription.get()), forDataDelivery);
    }

    /**
     * Check if the given package has been granted the
     * android.Manifest.permission#READ_MEDIA_VISUAL_USER_SELECTED permission.
     */
    public static boolean checkPermissionReadVisualUserSelected(
            @NonNull Context context,
            int pid,
            int uid,
            @NonNull String packageName,
            @Nullable String attributionTag,
            boolean targetSdkIsAtLeastT,
            boolean forDataDelivery) {
        if (!SdkLevel.isAtLeastU() || !targetSdkIsAtLeastT) {
            return false;
        }
        if (forDataDelivery) {
            return checkPermissionForDataDelivery(context, READ_MEDIA_VISUAL_USER_SELECTED, pid,
                    uid,
                    packageName, attributionTag,
                    generateAppOpMessage(packageName, sOpDescription.get()));
        } else {
            return checkPermissionForPreflight(context, READ_MEDIA_VISUAL_USER_SELECTED, pid,
                    uid,
                    packageName);
        }
    }

    /**
     * Check if the given package has been granted the
     * android.Manifest.permission#QUERY_ALL_PACKAGES permission.
     */
    public static boolean checkPermissionQueryAllPackages(@NonNull Context context, int pid,
            int uid, @NonNull String packageName, @Nullable String attributionTag) {
        return checkPermissionForDataDelivery(context, QUERY_ALL_PACKAGES, pid,
                uid, packageName, attributionTag, null);
    }

    /**
     * Check if the given package has been granted the
     * android.provider.MediaStore.#ACCESS_MEDIA_OWNER_PACKAGE_NAME_PERMISSION permission.
     */
    public static boolean checkPermissionAccessMediaOwnerPackageName(@NonNull Context context,
            int pid, int uid, @NonNull String packageName, @Nullable String attributionTag) {
        return checkPermissionForDataDelivery(context,
                MediaStore.ACCESS_MEDIA_OWNER_PACKAGE_NAME_PERMISSION,
                pid, uid, packageName, attributionTag, null);
    }

    /**
     * Check if the given package has been granted the
     * {@link android.provider.MediaStore#ACCESS_OEM_METADATA_PERMISSION} permission.
     */
    public static boolean checkPermissionAccessOemMetadata(@NonNull Context context,
            int pid, int uid, @NonNull String packageName, @Nullable String attributionTag) {
        return checkPermissionForDataDelivery(context, MediaStore.ACCESS_OEM_METADATA_PERMISSION,
                pid, uid, packageName, attributionTag, null);
    }

    /**
     * Check if the given package has been granted the
     * {@link android.provider.MediaStore#UPDATE_OEM_METADATA_PERMISSION} permission.
     */
    public static boolean checkPermissionUpdateOemMetadata(@NonNull Context context,
            int pid, int uid, @NonNull String packageName, @Nullable String attributionTag) {
        return checkPermissionForPreflight(context, MediaStore.UPDATE_OEM_METADATA_PERMISSION,
                pid, uid, packageName);
    }

    public static boolean checkPermissionInstallPackages(@NonNull Context context, int pid, int uid,
        @NonNull String packageName, @Nullable String attributionTag) {
        return checkPermissionForDataDelivery(context, INSTALL_PACKAGES, pid,
                uid, packageName, attributionTag, null);
    }

    public static boolean checkPermissionAccessMtp(@NonNull Context context, int pid, int uid,
        @NonNull String packageName, @Nullable String attributionTag) {
        return checkPermissionForDataDelivery(context, ACCESS_MTP, pid,
                uid, packageName, attributionTag, null);
    }

    /**
     * Returns {@code true} if the given package has write images or write video app op, which
     * indicates the package is a system gallery.
     */
    public static boolean checkWriteImagesOrVideoAppOps(@NonNull Context context, int uid,
            @NonNull String packageName, @Nullable String attributionTag, boolean forDataDelivery) {
        return checkAppOp(
                context, OPSTR_WRITE_MEDIA_IMAGES, uid, packageName, attributionTag,
                generateAppOpMessage(packageName, sOpDescription.get()), forDataDelivery)
                || checkAppOp(
                        context, OPSTR_WRITE_MEDIA_VIDEO, uid, packageName, attributionTag,
                generateAppOpMessage(packageName, sOpDescription.get()), forDataDelivery);
    }

    /**
     * Returns {@code true} if any package for the given uid has request_install_packages app op.
     */
    public static boolean checkAppOpRequestInstallPackagesForSharedUid(@NonNull Context context,
            int uid, @NonNull String[] sharedPackageNames, @Nullable String attributionTag) {
        for (String packageName : sharedPackageNames) {
            if (checkAppOp(context, OPSTR_REQUEST_INSTALL_PACKAGES, uid, packageName,
                    attributionTag, generateAppOpMessage(packageName, sOpDescription.get()),
                    /*forDataDelivery*/ false)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param context Application context
     * @param pid calling process ID
     * @param uid callers UID
     * @return true if the given uid has MANAGE_CLOUD_MEDIA_PROVIDERS_PERMISSION permission,
     * otherwise returns false.
     */
    public static boolean checkManageCloudMediaProvidersPermission(@NonNull Context context,
            int pid, @UserIdInt int uid) {
        return context.checkPermission(
                MANAGE_CLOUD_MEDIA_PROVIDERS_PERMISSION,
                pid,
                uid
        ) == PERMISSION_GRANTED;
    }

    @VisibleForTesting
    static boolean checkNoIsolatedStorageGranted(@NonNull Context context, int uid,
            @NonNull String packageName) {
        final AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        int ret = appOps.unsafeCheckOpNoThrow(OPSTR_NO_ISOLATED_STORAGE, uid, packageName);
        return ret == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * Generates a message to be used with the different {@link AppOpsManager#noteOp} variations.
     * If the supplied description is {@code null}, the returned message will be {@code null}.
     */
    private static String generateAppOpMessage(
            @NonNull String packageName, @Nullable String description) {
        if (description == null) {
            return null;
        }
        return "Package: " + packageName + ". Description: " + description + ".";
    }

    /**
     * Similar to {@link #checkPermissionForPreflight(Context, String, int, int, String)},
     * but also returns true for non-legacy apps.
     */
    private static boolean checkPermissionAllowingNonLegacy(@NonNull Context context,
            @NonNull String permission, int pid, int uid, @NonNull String packageName) {
        final AppOpsManager appOps = context.getSystemService(AppOpsManager.class);

        // Allowing non legacy apps to bypass this check
        if (appOps.unsafeCheckOpNoThrow(OPSTR_LEGACY_STORAGE, uid,
                packageName) != AppOpsManager.MODE_ALLOWED) return true;

        // Seems like it's a legacy app, so it has to pass the permission check
        return checkPermissionForPreflight(context, permission, pid, uid, packageName);
    }

    /**
     * Checks *only* App Ops.
     */
    private static boolean checkAppOp(@NonNull Context context,
            @NonNull String op, int uid, @NonNull String packageName,
            @Nullable String attributionTag, @Nullable String opMessage, boolean forDataDelivery) {
        final AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        final int mode = forDataDelivery ? appOps.noteOpNoThrow(op, uid, packageName,
                attributionTag, opMessage) : appOps.unsafeCheckOpNoThrow(op, uid, packageName);
        switch (mode) {
            case AppOpsManager.MODE_ALLOWED:
                return true;
            case AppOpsManager.MODE_DEFAULT:
            case AppOpsManager.MODE_IGNORED:
            case AppOpsManager.MODE_ERRORED:
                return false;
            default:
                throw new IllegalStateException(op + " has unknown mode " + mode);
        }
    }


    /**
     * Checks *only* App Ops, also returns true for legacy apps.
     */
    private static boolean checkAppOpAllowingLegacy(@NonNull Context context,
            @NonNull String op, int pid, int uid, @NonNull String packageName,
            @Nullable String attributionTag, @Nullable String opMessage, boolean forDataDelivery) {
        final AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        final int mode = forDataDelivery
                ? appOps.noteOpNoThrow(op, uid, packageName, attributionTag, opMessage)
                : appOps.unsafeCheckOpNoThrow(op, uid, packageName);
        switch (mode) {
            case AppOpsManager.MODE_ALLOWED:
                return true;
            case AppOpsManager.MODE_DEFAULT:
            case AppOpsManager.MODE_IGNORED:
            case AppOpsManager.MODE_ERRORED:
                // Legacy apps technically have the access granted by this op,
                // even when the op is denied
                if ((appOps.unsafeCheckOpNoThrow(OPSTR_LEGACY_STORAGE, uid,
                        packageName) == AppOpsManager.MODE_ALLOWED)) return true;

                return false;
            default:
                throw new IllegalStateException(op + " has unknown mode " + mode);
        }
    }

    /**
     * Checks whether a given package in a UID and PID has a given permission
     * and whether the app op that corresponds to this permission is allowed.
     *
     * <strong>NOTE:</strong> Use this method only for permission checks at the
     * preflight point where you will not deliver the permission protected data
     * to clients but schedule permission data delivery, apps register listeners,
     * etc.
     *
     * <p>For example, if an app registers a location listener it should have the location
     * permission but no data is actually sent to the app at the moment of registration
     * and you should use this method to determine if the app has or may have location
     * permission (if app has only foreground location the grant state depends on the app's
     * fg/gb state) and this check will not leave a trace that permission protected data
     * was delivered. When you are about to deliver the location data to a registered
     * listener you should use {@link #checkPermissionForDataDelivery(Context, String,
     * int, int, String, String, String)} which will evaluate the permission access based on the
     * current fg/bg state of the app and leave a record that the data was accessed.
     *
     * @param context Context for accessing resources.
     * @param permission The permission to check.
     * @param pid The process id for which to check.
     * @param uid The uid for which to check.
     * @param packageName The package name for which to check. If null the
     *     the first package for the calling UID will be used.
     * @return boolean if permission is {@link #PERMISSION_GRANTED}
     *
     * @see #checkPermissionForDataDelivery(Context, String, int, int, String, String, String)
     */
    private static boolean checkPermissionForPreflight(@NonNull Context context,
            @NonNull String permission, int pid, int uid, @Nullable String packageName) {
        return checkPermissionCommon(context, permission, pid, uid, packageName,
                null /*attributionTag*/, null /*message*/,
                false /*forDataDelivery*/);
    }

    /**
     * Checks whether a given package in a UID and PID has a given permission
     * and whether the app op that corresponds to this permission is allowed.
     *
     * <strong>NOTE:</strong> Use this method only for permission checks at the
     * point where you will deliver the permission protected data to clients.
     *
     * <p>For example, if an app registers a location listener it should have the location
     * permission but no data is actually sent to the app at the moment of registration
     * and you should use {@link #checkPermissionForPreflight(Context, String, int, int, String)}
     * to determine if the app has or may have location permission (if app has only foreground
     * location the grant state depends on the app's fg/gb state) and this check will not
     * leave a trace that permission protected data was delivered. When you are about to
     * deliver the location data to a registered listener you should use this method which
     * will evaluate the permission access based on the current fg/bg state of the app and
     * leave a record that the data was accessed.
     *
     * @param context Context for accessing resources.
     * @param permission The permission to check.
     * @param pid The process id for which to check. Use {@link #PID_UNKNOWN} if the PID
     *    is not known.
     * @param uid The uid for which to check.
     * @param packageName The package name for which to check. If null the
     *     the first package for the calling UID will be used.
     * @param attributionTag attribution tag
     * @return boolean true if {@link #PERMISSION_GRANTED}
     * @param message A message describing the reason the permission was checked
     *
     * @see #checkPermissionForPreflight(Context, String, int, int, String)
     */
    private static boolean checkPermissionForDataDelivery(@NonNull Context context,
            @NonNull String permission, int pid, int uid, @Nullable String packageName,
            @Nullable String attributionTag, @Nullable String message) {
        return checkPermissionCommon(context, permission, pid, uid, packageName, attributionTag,
                message, true /*forDataDelivery*/);
    }

    private static boolean checkPermissionCommon(@NonNull Context context,
            @NonNull String permission, int pid, int uid, @Nullable String packageName,
            @Nullable String attributionTag, @Nullable String message, boolean forDataDelivery) {
        if (packageName == null) {
            String[] packageNames = context.getPackageManager().getPackagesForUid(uid);
            if (packageNames != null && packageNames.length > 0) {
                packageName = packageNames[0];
            }
        }

        if (isAppOpPermission(permission)) {
            return checkAppOpPermission(context, permission, pid, uid, packageName, attributionTag,
                    message, forDataDelivery);
        }
        if (isRuntimePermission(permission)) {
            return checkRuntimePermission(context, permission, pid, uid, packageName,
                    attributionTag, message, forDataDelivery);
        }

        return context.checkPermission(permission, pid, uid) == PERMISSION_GRANTED;
    }

    private static boolean isAppOpPermission(String permission) {
        switch (permission) {
            case MANAGE_EXTERNAL_STORAGE:
            case MANAGE_MEDIA:
                return true;
        }
        return false;
    }

    private static boolean isRuntimePermission(String permission) {
        switch (permission) {
            case ACCESS_MEDIA_LOCATION:
            case READ_EXTERNAL_STORAGE:
            case WRITE_EXTERNAL_STORAGE:
            case READ_MEDIA_AUDIO:
            case READ_MEDIA_VIDEO:
            case READ_MEDIA_IMAGES:
            case READ_MEDIA_VISUAL_USER_SELECTED:
                return true;
        }
        return false;
    }

    private static boolean checkAppOpPermission(@NonNull Context context,
            @NonNull String permission, int pid, int uid, @Nullable String packageName,
            @Nullable String attributionTag, @Nullable String message, boolean forDataDelivery) {
        final String op = AppOpsManager.permissionToOp(permission);
        if (op == null || packageName == null) {
            return false;
        }

        final AppOpsManager appOpsManager = context.getSystemService(AppOpsManager.class);
        final int opMode = (forDataDelivery)
                ? appOpsManager.noteOpNoThrow(op, uid, packageName, attributionTag, message)
                : appOpsManager.unsafeCheckOpRawNoThrow(op, uid, packageName);

        switch (opMode) {
            case AppOpsManager.MODE_ALLOWED:
            case AppOpsManager.MODE_FOREGROUND:
                return true;
            case AppOpsManager.MODE_DEFAULT:
                return context.checkPermission(permission, pid, uid) == PERMISSION_GRANTED;
            default:
                return false;
        }
    }

    private static boolean checkRuntimePermission(@NonNull Context context,
            @NonNull String permission, int pid, int uid, @Nullable String packageName,
            @Nullable String attributionTag, @Nullable String message, boolean forDataDelivery) {
        if (context.checkPermission(permission, pid, uid) == PackageManager.PERMISSION_DENIED) {
            return false;
        }

        final String op = AppOpsManager.permissionToOp(permission);
        if (op == null || packageName == null) {
            return true;
        }

        final AppOpsManager appOpsManager = context.getSystemService(AppOpsManager.class);
        final int opMode = (forDataDelivery)
                ? appOpsManager.noteOpNoThrow(op, uid, packageName, attributionTag, message)
                : appOpsManager.unsafeCheckOpRawNoThrow(op, uid, packageName);

        switch (opMode) {
            case AppOpsManager.MODE_ALLOWED:
            case AppOpsManager.MODE_FOREGROUND:
                return true;
            default:
                return false;
        }
    }
}
