/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.internal.util;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.UserHandle;

import lineageos.providers.LineageSettings;

public final class PowerMenuUtils {
    public static boolean isAdvancedRestartPossible(final Context context) {
        KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        boolean keyguardLocked = km.inKeyguardRestrictedInputMode() && km.isKeyguardSecure();
        boolean advancedRestartEnabled = LineageSettings.Secure.getInt(context.getContentResolver(),
                LineageSettings.Secure.ADVANCED_REBOOT, 0) == 1;
        boolean isPrimaryUser = UserHandle.getCallingUserId() == UserHandle.USER_SYSTEM;

        return advancedRestartEnabled && !keyguardLocked && isPrimaryUser;
    }

    /**
     * Returns the apps pinned to the power menu, a '|' separated list of package names. Until
     * the user saves their own choice, that's the device's config_powerMenuDefaultPinnedApps.
     */
    public static String getPinnedApps(final Context context, final int userId) {
        final String pinned = LineageSettings.Secure.getStringForUser(
                context.getContentResolver(), LineageSettings.Secure.POWER_MENU_PINNED_APPS,
                userId);
        if (pinned != null) {
            return pinned;
        }
        return String.join("|", context.getResources().getStringArray(
                org.lineageos.platform.internal.R.array.config_powerMenuDefaultPinnedApps));
    }
}
