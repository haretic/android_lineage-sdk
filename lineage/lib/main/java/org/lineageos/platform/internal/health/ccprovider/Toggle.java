/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.platform.internal.health.ccprovider;

import static lineageos.health.HealthInterface.MODE_AUTO;
import static lineageos.health.HealthInterface.MODE_LIMIT;
import static lineageos.health.HealthInterface.MODE_MANUAL;

import android.content.Context;
import android.util.Log;

import org.lineageos.platform.internal.R;

import vendor.lineage.health.ChargingControlSupportedMode;
import vendor.lineage.health.IChargingControl;

import java.io.PrintWriter;

public class Toggle extends ChargingControlProvider {
    protected final int mChargingLimitMargin;

    private final boolean mToggleSetAlways = mContext.getResources().getBoolean(
            R.bool.config_chargingControlToggleSetAlways);
    private boolean mIsLimitSet;
    private final ChargeSchedule mSchedule;

    public Toggle(IChargingControl chargingControl,
            Context context) {
        super(context, chargingControl);

        boolean isBypassSupported = isHALModeSupported(
                ChargingControlSupportedMode.BYPASS | ChargingControlSupportedMode.TOGGLE);
        if (!isBypassSupported) {
            mChargingLimitMargin = mContext.getResources().getInteger(
                    R.integer.config_chargingControlBatteryRechargeMargin);
        } else {
            mChargingLimitMargin = 1;
        }
        Log.i(TAG, "isBypassSupported: " + isBypassSupported);

        mSchedule = new ChargeSchedule(context);
    }

    @Override
    public boolean isSupported() {
        return isHALModeSupported(ChargingControlSupportedMode.TOGGLE);
    }

    @Override
    public boolean requiresBatteryLevelMonitoring() {
        return !isHALModeSupported(ChargingControlSupportedMode.BYPASS);
    }

    @Override
    protected boolean onBatteryChanged(float currentPct, int targetPct) {
        mIsLimitSet = shouldStopCharging(currentPct, targetPct);
        Log.i(TAG, "Current battery level: " + currentPct + ", target: " + targetPct +
                ", limit set: " + mIsLimitSet);
        return setChargingEnabled(!mIsLimitSet);
    }

    private boolean onStage(ChargeSchedule.Stage stage) {
        switch (stage) {
            case STAGE_NONE -> {
                setChargingEnabled(true);
                return false;
            }
            case STAGE_INITIAL, STAGE_CONTINUE -> {
                return setChargingEnabled(true);
            }
            case STAGE_WAITING -> {
                return setChargingEnabled(false);
            }
        }

        return false;
    }

    @Override
    protected boolean onBatteryChanged(float batteryPct, long startTime, long targetTime,
            int configMode) {
        if (configMode != MODE_AUTO && configMode != MODE_MANUAL) {
            Log.e(TAG,
                    "Possible bug: onBatteryChanged called with unsupported mode: " + configMode);
            return false;
        }

        return onStage(mSchedule.update(batteryPct, startTime, targetTime));
    }

    private boolean setChargingEnabled(boolean enabled) {
        try {
            if (mToggleSetAlways) {
                mChargingControl.setChargingEnabled(enabled);
            } else if (mChargingControl.getChargingEnabled() != enabled) {
                mChargingControl.setChargingEnabled(enabled);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to set charging enabled", e);
            return false;
        }
    }

    @Override
    protected void onEnabled() {
        onReset();
    }

    @Override
    protected void onDisable() {
        onReset();
    }

    @Override
    protected void onReset() {
        try {
            mChargingControl.setChargingEnabled(true);
            mIsLimitSet = false;
            mSchedule.reset();
        } catch (Exception e) {
            Log.e(TAG, "Failed to set charging enabled", e);
        }
    }

    @Override
    public boolean isChargingControlModeSupported(int mode) {
        return mode == MODE_AUTO || mode == MODE_MANUAL || mode == MODE_LIMIT;
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println("Provider: " + getClass().getName());
        pw.println("  mIsLimitSet: " + mIsLimitSet);
        mSchedule.dump(pw);
        pw.println("  mChargeLimitMargin: " + mChargingLimitMargin);
    }

    private boolean shouldStopCharging(float currentPct, int targetPct) {
        if (mIsLimitSet) {
            return currentPct >= targetPct - mChargingLimitMargin;
        }
        return currentPct >= targetPct;
    }
}
