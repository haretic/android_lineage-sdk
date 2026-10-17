/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.platform.internal.health.ccprovider;

import static org.lineageos.platform.internal.health.Util.msToHMSString;
import static org.lineageos.platform.internal.health.Util.msToString;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.BatteryStatsManager;
import android.os.BatteryUsageStats;
import android.util.Log;

import org.lineageos.platform.internal.R;

import java.io.PrintWriter;
import java.util.Objects;

/**
 * Stage machine for the scheduled modes, shared by the providers that implement them.
 */
class ChargeSchedule {
    private static final String TAG = "LineageHealth";

    enum Stage {
        /**
         * It has no effect
         */
        STAGE_NONE,

        /**
         * The battery level is less than 80%
         */
        STAGE_INITIAL,

        /**
         * The battery level reached 80% and is now waiting
         */
        STAGE_WAITING,

        /**
         * The battery is now charging towards 100%
         */
        STAGE_CONTINUE,
    }

    // Only when the battery level is above this limit will the charging control be activated.
    static final int CHARGE_CTRL_MIN_LEVEL = 80;

    private final Context mContext;
    private final int mChargingTimeMargin;

    private long mSavedTargetTime;
    private long mEstimatedFullTime;
    private Stage mStage = Stage.STAGE_NONE;

    ChargeSchedule(Context context) {
        mContext = context;
        mChargingTimeMargin = mContext.getResources().getInteger(
                R.integer.config_chargingControlTimeMargin) * 60 * 1000;
    }

    Stage update(float batteryPct, long startTime, long targetTime) {
        Stage prevStage = mStage;
        mStage = getNextStage(batteryPct, startTime, targetTime);
        Log.i(TAG, "State change: " + prevStage + " -> " + mStage);
        return mStage;
    }

    void reset() {
        mSavedTargetTime = 0;
        mEstimatedFullTime = 0;
        mStage = Stage.STAGE_NONE;
    }

    void dump(PrintWriter pw) {
        pw.println("  mSavedTargetTime: " + msToString(mContext, mSavedTargetTime));
        pw.println("  mEstimatedFullTime: " + msToHMSString(mEstimatedFullTime));
        pw.println("  mStage: " + mStage);
    }

    private Stage getNextStage(float batteryPct, long startTime, long targetTime) {
        final long currentTime = System.currentTimeMillis();
        Stage stage = mStage;

        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = mContext.registerReceiver(null, ifilter, Context.RECEIVER_EXPORTED);
        boolean plugged = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) != 0;

        if (startTime > currentTime && stage != Stage.STAGE_CONTINUE) {
            // Not yet entering user configured time frame
            return Stage.STAGE_NONE;
        }

        if (mSavedTargetTime != targetTime && (mSavedTargetTime == 0
                || mSavedTargetTime >= currentTime)) {
            Log.i(TAG, "User changed target time, reassign it");
            mSavedTargetTime = targetTime;
            stage = Stage.STAGE_INITIAL;
        }

        final BatteryUsageStats batteryUsageStats = Objects.requireNonNull(
                mContext.getSystemService(
                        BatteryStatsManager.class)).getBatteryUsageStats();
        long remaining = batteryUsageStats.getChargeTimeRemainingMs();
        remaining += mChargingTimeMargin;
        Log.i(TAG, "Current estimated time to full: " + msToHMSString(remaining));

        long deltaTime = targetTime - currentTime;
        Log.i(TAG, "Current time to target: " + msToHMSString(deltaTime));

        switch (stage) {
            case STAGE_NONE, STAGE_INITIAL -> {
                if (!plugged || batteryPct < CHARGE_CTRL_MIN_LEVEL || remaining == -1) {
                    // NONE/INITIAL -> INITIAL: If battery level < 80%
                    return Stage.STAGE_INITIAL;
                } else if (deltaTime > remaining) {
                    // NONE/INITIAL -> WAITING: battery level >= 80% && Have enough time waiting
                    mEstimatedFullTime = remaining;
                    return Stage.STAGE_WAITING;
                } else {
                    // NONE/INITIAL -> CONTINUE: battery level >= 80% && Not enough time waiting
                    return Stage.STAGE_CONTINUE;
                }
            }
            case STAGE_WAITING -> {
                if (deltaTime <= mEstimatedFullTime) {
                    return Stage.STAGE_CONTINUE;
                } else {
                    return Stage.STAGE_WAITING;
                }
            }
            case STAGE_CONTINUE -> {
                if (!plugged) {
                    return Stage.STAGE_INITIAL;
                }
                return Stage.STAGE_CONTINUE;
            }
        }

        Log.e(TAG, "Possible bug: code reaches out of switch case");
        return Stage.STAGE_NONE;
    }
}
