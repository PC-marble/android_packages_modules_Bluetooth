/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.bluetooth;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.os.PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED;
import static android.permission.PermissionManager.PERMISSION_GRANTED;
import static android.permission.PermissionManager.PERMISSION_HARD_DENIED;

import static com.android.server.bluetooth.BluetoothAirplaneModeListener.APM_BT_ENABLED_NOTIFICATION;
import static com.android.server.bluetooth.BluetoothAirplaneModeListener.APM_ENHANCEMENT;
import static com.android.server.bluetooth.BluetoothAirplaneModeListener.APM_USER_TOGGLED_BLUETOOTH;
import static com.android.server.bluetooth.BluetoothAirplaneModeListener.BLUETOOTH_APM_STATE;
import static com.android.server.bluetooth.BluetoothAirplaneModeListener.NOTIFICATION_NOT_SHOWN;
import static com.android.server.bluetooth.BluetoothAirplaneModeListener.USED;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.BroadcastOptions;
import android.app.admin.DevicePolicyManager;
import android.app.compat.CompatChanges;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProtoEnums;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.IBluetooth;
import android.bluetooth.IBluetoothCallback;
import android.bluetooth.IBluetoothGatt;
import android.bluetooth.IBluetoothManager;
import android.bluetooth.IBluetoothManagerCallback;
import android.bluetooth.IBluetoothProfileServiceConnection;
import android.bluetooth.IBluetoothStateChangeCallback;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.content.AttributionSource;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.PowerExemptionManager;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.permission.PermissionManager;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.sysprop.BluetoothProperties;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.util.proto.ProtoOutputStream;

import com.android.bluetooth.BluetoothStatsLog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.SynchronousResultReceiver;
import com.android.server.BluetoothManagerServiceDumpProto;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class BluetoothManagerService extends IBluetoothManager.Stub {
    private static final String TAG = "BluetoothManagerService";
    private static final boolean DBG = true;

    private static final String BLUETOOTH_PRIVILEGED =
            android.Manifest.permission.BLUETOOTH_PRIVILEGED;

    private static final int ACTIVE_LOG_MAX_SIZE = 20;
    private static final int CRASH_LOG_MAX_SIZE = 100;

    private static final int DEFAULT_REBIND_COUNT = 3;
    private static final int TIMEOUT_BIND_MS = 3000; //Maximum msec to wait for a bind

    /**
     * Timeout value for synchronous binder call
     */
    private static final Duration SYNC_CALLS_TIMEOUT = Duration.ofSeconds(3);

    /**
     * @return timeout value for synchronous binder call
     */
    private static Duration getSyncTimeout() {
        return SYNC_CALLS_TIMEOUT;
    }

    //Maximum msec to wait for service restart
    private static final int SERVICE_RESTART_TIME_MS = 400;
    //Maximum msec to wait for restart due to error
    private static final int ERROR_RESTART_TIME_MS = 3000;
    //Maximum msec to delay MESSAGE_USER_SWITCHED
    private static final int USER_SWITCHED_TIME_MS = 200;
    // Delay for the addProxy function in msec
    private static final int ADD_PROXY_DELAY_MS = 100;
    // Delay for retrying enable and disable in msec
    private static final int ENABLE_DISABLE_DELAY_MS = 300;
    private static final int DELAY_BEFORE_RESTART_DUE_TO_INIT_FLAGS_CHANGED_MS = 300;
    private static final int DELAY_FOR_RETRY_INIT_FLAG_CHECK_MS = 86400000;

    private static final int MESSAGE_ENABLE = 1;
    @VisibleForTesting
    static final int MESSAGE_DISABLE = 2;
    private static final int MESSAGE_HANDLE_ENABLE_DELAYED = 3;
    private static final int MESSAGE_HANDLE_DISABLE_DELAYED = 4;
    private static final int MESSAGE_INFORM_ADAPTER_SERVICE_UP = 22;
    private static final int MESSAGE_REGISTER_STATE_CHANGE_CALLBACK = 30;
    private static final int MESSAGE_UNREGISTER_STATE_CHANGE_CALLBACK = 31;
    private static final int MESSAGE_BLUETOOTH_SERVICE_CONNECTED = 40;
    private static final int MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED = 41;
    private static final int MESSAGE_RESTART_BLUETOOTH_SERVICE = 42;
    private static final int MESSAGE_BLUETOOTH_STATE_CHANGE = 60;
    private static final int MESSAGE_TIMEOUT_BIND = 100;
    private static final int MESSAGE_TIMEOUT_UNBIND = 101;
    private static final int MESSAGE_GET_NAME_AND_ADDRESS = 200;
    private static final int MESSAGE_USER_SWITCHED = 300;
    private static final int MESSAGE_USER_UNLOCKED = 301;
    private static final int MESSAGE_ADD_PROXY_DELAYED = 400;
    private static final int MESSAGE_BIND_PROFILE_SERVICE = 401;
    private static final int MESSAGE_RESTORE_USER_SETTING = 500;
    private static final int MESSAGE_INIT_FLAGS_CHANGED = 600;

    private static final int RESTORE_SETTING_TO_ON = 1;
    private static final int RESTORE_SETTING_TO_OFF = 0;

    private static final int MAX_ERROR_RESTART_RETRIES = 6;
    private static final int MAX_WAIT_FOR_ENABLE_DISABLE_RETRIES = 10;

    // Bluetooth persisted setting is off
    private static final int BLUETOOTH_OFF = 0;
    // Bluetooth persisted setting is on
    // and Airplane mode won't affect Bluetooth state at start up
    private static final int BLUETOOTH_ON_BLUETOOTH = 1;
    // Bluetooth persisted setting is on
    // but Airplane mode will affect Bluetooth state at start up
    // and Airplane mode will have higher priority.
    @VisibleForTesting
    static final int BLUETOOTH_ON_AIRPLANE = 2;

    private static final int BLUETOOTH_OFF_APM = 0;
    private static final int BLUETOOTH_ON_APM = 1;

    private static final int SERVICE_IBLUETOOTH = 1;
    private static final int SERVICE_IBLUETOOTHGATT = 2;

    private static final int FLAGS_SYSTEM_APP =
            ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;

    /**
     * Starting with {@link android.os.Build.VERSION_CODES#TIRAMISU}, applications are
     * not allowed to enable/disable Bluetooth.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = android.os.Build.VERSION_CODES.TIRAMISU)
    static final long RESTRICT_ENABLE_DISABLE = 218493289L;
    // APM enhancement feature is enabled by default
    // Set this value to 0 to disable the feature
    private static final int DEFAULT_APM_ENHANCEMENT_STATE = 1;

    private final Context mContext;

    private final UserManager mUserManager;

    // -3     match with Userhandle.USER_CURRENT_OR_SELF
    private static final UserHandle USER_HANDLE_CURRENT_OR_SELF = UserHandle.of(-3);
    // -10000 match with Userhandle.USER_NULL
    private static final UserHandle USER_HANDLE_NULL = UserHandle.of(-10000);

    // Locks are not provided for mName and mAddress.
    // They are accessed in handler or broadcast receiver, same thread context.
    private String mAddress;
    private String mName;
    private final ContentResolver mContentResolver;
    private final RemoteCallbackList<IBluetoothManagerCallback> mCallbacks;
    private final RemoteCallbackList<IBluetoothStateChangeCallback> mStateChangeCallbacks;
    private IBinder mBluetoothBinder;

    private final ReentrantReadWriteLock mBluetoothLock = new ReentrantReadWriteLock();
    @GuardedBy("mBluetoothLock")
    private IBluetooth mBluetooth;

    private IBluetoothGatt mBluetoothGatt;
    private boolean mBinding;
    private int mBindingUserID;
    private boolean mUnbinding;
    private boolean mTryBindOnBindTimeout = false;
    private List<Integer> mSupportedProfileList = new ArrayList<>();

    private BluetoothModeChangeHelper mBluetoothModeChangeHelper;

    private BluetoothAirplaneModeListener mBluetoothAirplaneModeListener;

    private BluetoothDeviceConfigListener mBluetoothDeviceConfigListener;

    private BluetoothNotificationManager mBluetoothNotificationManager;

    private BluetoothSatelliteModeListener mBluetoothSatelliteModeListener;

    // used inside handler thread
    private boolean mQuietEnable = false;
    private boolean mEnable;
    private boolean mShutdownInProgress = false;

    private static String timeToLog(long timestamp) {
        return DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(timestamp));
    }

    /**
     * Used for tracking apps that enabled / disabled Bluetooth.
     */
    private class ActiveLog {
        private int mReason;
        private String mPackageName;
        private boolean mEnable;
        private long mTimestamp;

        ActiveLog(int reason, String packageName, boolean enable, long timestamp) {
            mReason = reason;
            mPackageName = packageName;
            mEnable = enable;
            mTimestamp = timestamp;
        }

        public String toString() {
            return timeToLog(mTimestamp) + (mEnable ? "  Enabled " : " Disabled ")
                    + " due to " + getEnableDisableReasonString(mReason) + " by " + mPackageName;
        }

        void dump(ProtoOutputStream proto) {
            proto.write(BluetoothManagerServiceDumpProto.ActiveLog.TIMESTAMP_MS, mTimestamp);
            proto.write(BluetoothManagerServiceDumpProto.ActiveLog.ENABLE, mEnable);
            proto.write(BluetoothManagerServiceDumpProto.ActiveLog.PACKAGE_NAME, mPackageName);
            proto.write(BluetoothManagerServiceDumpProto.ActiveLog.REASON, mReason);
        }
    }

    private final LinkedList<ActiveLog> mActiveLogs = new LinkedList<>();
    private final LinkedList<Long> mCrashTimestamps = new LinkedList<>();
    private int mCrashes;
    private long mLastEnabledTime;

    // configuration from external IBinder call which is used to
    // synchronize with broadcast receiver.
    private boolean mQuietEnableExternal;
    private boolean mEnableExternal;

    // Map of apps registered to keep BLE scanning on.
    private Map<IBinder, ClientDeathRecipient> mBleApps =
            new ConcurrentHashMap<IBinder, ClientDeathRecipient>();

    private int mState;
    private final HandlerThread mBluetoothHandlerThread;
    private final BluetoothHandler mHandler;
    private int mErrorRecoveryRetryCounter;
    private final int mSystemUiUid;

    private boolean mIsHearingAidProfileSupported;

    private AppOpsManager mAppOps;

    // Save a ProfileServiceConnections object for each of the bound
    // bluetooth profile services
    private final Map<Integer, ProfileServiceConnections> mProfileServices = new HashMap<>();
    @GuardedBy("mProfileServices")
    private boolean mUnbindingAll = false;

    private final IBluetoothCallback mBluetoothCallback = new IBluetoothCallback.Stub() {
        @Override
        public void onBluetoothStateChange(int prevState, int newState) throws RemoteException {
            Message msg =
                    mHandler.obtainMessage(MESSAGE_BLUETOOTH_STATE_CHANGE, prevState, newState);
            mHandler.sendMessage(msg);
        }
    };

    public void onUserRestrictionsChanged(UserHandle userHandle) {
        final boolean newBluetoothDisallowed = mUserManager.hasUserRestrictionForUser(
                UserManager.DISALLOW_BLUETOOTH, userHandle);
        // Disallow Bluetooth sharing when either Bluetooth is disallowed or Bluetooth sharing
        // is disallowed
        final boolean newBluetoothSharingDisallowed = mUserManager.hasUserRestrictionForUser(
                UserManager.DISALLOW_BLUETOOTH_SHARING, userHandle) || newBluetoothDisallowed;

        // Disable OPP activities for this userHandle
        updateOppLauncherComponentState(userHandle, newBluetoothSharingDisallowed);

        // DISALLOW_BLUETOOTH can only be set by DO or PO on the system user.
        // Only trigger once instead of for all users
        if (userHandle == UserHandle.SYSTEM && newBluetoothDisallowed) {
            sendDisableMsg(BluetoothProtoEnums.ENABLE_DISABLE_REASON_DISALLOWED,
                    mContext.getPackageName());
        }
    }

    @VisibleForTesting
    public void onInitFlagsChanged() {
        // TODO(b/265386284)
    }

    public boolean onFactoryReset(AttributionSource attributionSource) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED,
                "Need BLUETOOTH_PRIVILEGED permission");

        // Wait for stable state if bluetooth is temporary state.
        int state = getState();
        if (state == BluetoothAdapter.STATE_BLE_TURNING_ON
                || state == BluetoothAdapter.STATE_TURNING_ON
                || state == BluetoothAdapter.STATE_TURNING_OFF) {
            if (!waitForState(Set.of(BluetoothAdapter.STATE_BLE_ON, BluetoothAdapter.STATE_ON))) {
                return false;
            }
        }

        // Clear registered LE apps to force shut-off Bluetooth
        clearBleApps();
        state = getState();
        mBluetoothLock.readLock().lock();
        try {
            if (mBluetooth == null) {
                return false;
            }
            if (state == BluetoothAdapter.STATE_BLE_ON) {
                addActiveLog(
                        BluetoothProtoEnums.ENABLE_DISABLE_REASON_FACTORY_RESET,
                        mContext.getPackageName(), false);
                synchronousOnBrEdrDown(attributionSource);
                return true;
            } else if (state == BluetoothAdapter.STATE_ON) {
                addActiveLog(
                        BluetoothProtoEnums.ENABLE_DISABLE_REASON_FACTORY_RESET,
                        mContext.getPackageName(), false);
                synchronousDisable(attributionSource);
                return true;
            }
        } catch (RemoteException | TimeoutException e) {
            Log.e(TAG, "Unable to shutdown Bluetooth", e);
        } finally {
            mBluetoothLock.readLock().unlock();
        }
        return false;
    }

    private int estimateBusyTime(int state) {
        if (state == BluetoothAdapter.STATE_BLE_ON && isBluetoothPersistedStateOn()) {
            // Bluetooth is in BLE and is starting classic
            return SERVICE_RESTART_TIME_MS;
        } else if (state != BluetoothAdapter.STATE_ON && state != BluetoothAdapter.STATE_OFF
                && state != BluetoothAdapter.STATE_BLE_ON) {
            // Bluetooth is turning state
            return ADD_PROXY_DELAY_MS;
        } else if (mHandler.hasMessages(MESSAGE_ENABLE)
                || mHandler.hasMessages(MESSAGE_DISABLE)
                || mHandler.hasMessages(MESSAGE_HANDLE_ENABLE_DELAYED)
                || mHandler.hasMessages(MESSAGE_HANDLE_DISABLE_DELAYED)
                || mHandler.hasMessages(MESSAGE_RESTART_BLUETOOTH_SERVICE)
                || mHandler.hasMessages(MESSAGE_TIMEOUT_BIND)
                || mHandler.hasMessages(MESSAGE_BIND_PROFILE_SERVICE)) {
            // Bluetooth is restarting
            return SERVICE_RESTART_TIME_MS;
        }
        return 0;
    }

    private void delayModeChangedIfNeeded(Object token, Runnable r, String modechanged) {
        mHandler.removeCallbacksAndMessages(token);

        final int state = getState();
        final int delayMs = estimateBusyTime(state);
        Log.d(TAG, "delayModeChangedIfNeeded(" + modechanged + "): "
                + "state=" + BluetoothAdapter.nameForState(state)
                + ", isAirplaneModeOn()=" + isAirplaneModeOn()
                + ", isSatelliteModeSensitive()=" + isSatelliteModeSensitive()
                + ", isSatelliteModeOn()=" + isSatelliteModeOn()
                + ", delayed=" + delayMs + "ms");

        if (delayMs > 0) {
            mHandler.postDelayed(() -> delayModeChangedIfNeeded(token, r, modechanged),
                    token, delayMs);
        } else {
            r.run();
        }
    }

    private static final Object ON_AIRPLANE_MODE_CHANGED_TOKEN = new Object();
    private static final Object ON_SATELLITE_MODE_CHANGED_TOKEN = new Object();
    private static final Object ON_SWITCH_USER_TOKEN = new Object();

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    void onAirplaneModeChanged() {
        delayModeChangedIfNeeded(ON_AIRPLANE_MODE_CHANGED_TOKEN,
                () -> handleAirplaneModeChanged(), "onAirplaneModeChanged");
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    void onSatelliteModeChanged() {
        delayModeChangedIfNeeded(ON_SATELLITE_MODE_CHANGED_TOKEN,
                () -> handleSatelliteModeChanged(), "onSatelliteModeChanged");
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    void onSwitchUser(UserHandle userHandle) {
        delayModeChangedIfNeeded(ON_SWITCH_USER_TOKEN,
                () -> handleSwitchUser(userHandle), "onSwitchUser");
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    private void handleAirplaneModeChanged() {
        synchronized (this) {
            if (isBluetoothPersistedStateOn()) {
                if (isAirplaneModeOn()) {
                    persistBluetoothSetting(BLUETOOTH_ON_AIRPLANE);
                } else {
                    persistBluetoothSetting(BLUETOOTH_ON_BLUETOOTH);
                }
            }

            int st = BluetoothAdapter.STATE_OFF;
            try {
                mBluetoothLock.readLock().lock();
                st = synchronousGetState();
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, "Unable to call getState", e);
                return;
            } finally {
                mBluetoothLock.readLock().unlock();
            }

            Log.d(TAG,
                    "Airplane Mode change - current state:  " + BluetoothAdapter.nameForState(
                            st) + ", isAirplaneModeOn()=" + isAirplaneModeOn());

            if (isAirplaneModeOn()) {
                // Clear registered LE apps to force shut-off
                clearBleApps();

                // If state is BLE_ON make sure we trigger disableBLE
                if (st == BluetoothAdapter.STATE_BLE_ON) {
                    mBluetoothLock.readLock().lock();
                    try {
                        if (mBluetooth != null) {
                            addActiveLog(
                                    BluetoothProtoEnums.ENABLE_DISABLE_REASON_AIRPLANE_MODE,
                                    mContext.getPackageName(), false);
                            synchronousOnBrEdrDown(mContext.getAttributionSource());
                            mEnable = false;
                        }
                    } catch (RemoteException | TimeoutException e) {
                        Log.e(TAG, "Unable to call onBrEdrDown", e);
                    } finally {
                        mBluetoothLock.readLock().unlock();
                    }
                } else {
                    Log.d(TAG, "Airplane ON: sendDisableMsg");
                    sendDisableMsg(BluetoothProtoEnums.ENABLE_DISABLE_REASON_AIRPLANE_MODE,
                            mContext.getPackageName());
                }
            } else if (mEnableExternal) {
                if (isBluetoothPersistedStateOn()) {
                    Log.d(TAG, "Airplane OFF: sendEnableMsg");
                    sendEnableMsg(mQuietEnableExternal,
                            BluetoothProtoEnums.ENABLE_DISABLE_REASON_AIRPLANE_MODE,
                            mContext.getPackageName());
                }
            }
        }
    }

    private void handleSatelliteModeChanged() {
        if (shouldBluetoothBeOn() && getState() != BluetoothAdapter.STATE_ON) {
            sendEnableMsg(mQuietEnableExternal,
                    BluetoothProtoEnums.ENABLE_DISABLE_REASON_SATELLITE_MODE,
                    mContext.getPackageName());
        } else if (!shouldBluetoothBeOn() && getState() != BluetoothAdapter.STATE_OFF) {
            sendDisableMsg(BluetoothProtoEnums.ENABLE_DISABLE_REASON_SATELLITE_MODE,
                    mContext.getPackageName());
        }
    }

    private boolean shouldBluetoothBeOn() {
        if (!isBluetoothPersistedStateOn()) {
            Log.d(TAG, "shouldBluetoothBeOn: User want BT off.");
            return false;
        }

        if (isSatelliteModeOn()) {
            Log.d(TAG, "shouldBluetoothBeOn: BT should be off as satellite mode is on.");
            return false;
        }

        if (isAirplaneModeOn() && isBluetoothPersistedStateOnAirplane()) {
            Log.d(TAG, "shouldBluetoothBeOn: BT should be off as airplaneMode is on.");
            return false;
        }

        Log.d(TAG, "shouldBluetoothBeOn: BT should be on.");
        return true;
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED.equals(action)) {
                String newName = intent.getStringExtra(BluetoothAdapter.EXTRA_LOCAL_NAME);
                if (DBG) {
                    Log.d(TAG, "Bluetooth Adapter name changed to " + newName + " by "
                            + mContext.getPackageName());
                }
                if (newName != null) {
                    storeNameAndAddress(newName, null);
                }
            } else if (BluetoothAdapter.ACTION_BLUETOOTH_ADDRESS_CHANGED.equals(action)) {
                String newAddress = intent.getStringExtra(BluetoothAdapter.EXTRA_BLUETOOTH_ADDRESS);
                if (newAddress != null) {
                    if (DBG) {
                        Log.d(TAG, "Bluetooth Adapter address changed to " + newAddress);
                    }
                    storeNameAndAddress(null, newAddress);
                } else {
                    if (DBG) {
                        Log.e(TAG, "No Bluetooth Adapter address parameter found");
                    }
                }
            } else if (Intent.ACTION_SETTING_RESTORED.equals(action)) {
                final String name = intent.getStringExtra(Intent.EXTRA_SETTING_NAME);
                if (Settings.Global.BLUETOOTH_ON.equals(name)) {
                    // The Bluetooth On state may be changed during system restore.
                    final String prevValue =
                            intent.getStringExtra(Intent.EXTRA_SETTING_PREVIOUS_VALUE);
                    final String newValue = intent.getStringExtra(Intent.EXTRA_SETTING_NEW_VALUE);

                    if (DBG) {
                        Log.d(TAG,
                                "ACTION_SETTING_RESTORED with BLUETOOTH_ON, prevValue=" + prevValue
                                        + ", newValue=" + newValue);
                    }

                    if ((newValue != null) && (prevValue != null) && !prevValue.equals(newValue)) {
                        Message msg = mHandler.obtainMessage(MESSAGE_RESTORE_USER_SETTING,
                                newValue.equals("0") ? RESTORE_SETTING_TO_OFF
                                        : RESTORE_SETTING_TO_ON, 0);
                        mHandler.sendMessage(msg);
                    }
                }
            } else if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)
                    || BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED.equals(action)
                    || BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED.equals(action)) {
                final int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                        BluetoothProfile.STATE_CONNECTED);
                if (mHandler.hasMessages(MESSAGE_INIT_FLAGS_CHANGED)
                        && state == BluetoothProfile.STATE_DISCONNECTED
                        && !mBluetoothModeChangeHelper.isMediaProfileConnected()) {
                    Log.i(TAG, "Device disconnected, reactivating pending flag changes");
                    onInitFlagsChanged();
                }
            } else if (action.equals(Intent.ACTION_SHUTDOWN)) {
                Log.i(TAG, "Device is shutting down.");
                mShutdownInProgress = true;
                mBluetoothLock.readLock().lock();
                try {
                    mEnable = false;
                    mEnableExternal = false;
                    if (mBluetooth != null && (mState == BluetoothAdapter.STATE_BLE_ON)) {
                        synchronousOnBrEdrDown(mContext.getAttributionSource());
                    } else if (mBluetooth != null && (mState == BluetoothAdapter.STATE_ON)) {
                        synchronousDisable(mContext.getAttributionSource());
                    }
                } catch (RemoteException | TimeoutException e) {
                    Log.e(TAG, "Unable to shutdown Bluetooth", e);
                } finally {
                    mBluetoothLock.readLock().unlock();
                }
            }
        }
    };

    BluetoothManagerService(Context context) {
        mBluetoothHandlerThread = BluetoothServerProxy.getInstance()
                .createHandlerThread("BluetoothManagerService");
        mBluetoothHandlerThread.start();

        mHandler = BluetoothServerProxy.getInstance().newBluetoothHandler(
                new BluetoothHandler(mBluetoothHandlerThread.getLooper()));

        mContext = context;

        mCrashes = 0;
        mBluetooth = null;
        mBluetoothBinder = null;
        mBluetoothGatt = null;
        mBinding = false;
        mTryBindOnBindTimeout = false;
        mUnbinding = false;
        mEnable = false;
        mState = BluetoothAdapter.STATE_OFF;
        mQuietEnableExternal = false;
        mEnableExternal = false;
        mAddress = null;
        mName = null;
        mErrorRecoveryRetryCounter = 0;
        mContentResolver = context.getContentResolver();

        // Observe BLE scan only mode settings change.
        registerForBleScanModeChange();
        mCallbacks = new RemoteCallbackList<IBluetoothManagerCallback>();
        mStateChangeCallbacks = new RemoteCallbackList<IBluetoothStateChangeCallback>();

        mUserManager = mContext.getSystemService(UserManager.class);

        mBluetoothNotificationManager = new BluetoothNotificationManager(mContext);

        // Disable ASHA if BLE is not supported, overriding any system property
        if (!isBleSupported(mContext)) {
            mIsHearingAidProfileSupported = false;
        } else {
            // ASHA default value is:
            //   * disabled on Automotive, TV, and Watch.
            //   * enabled for other form factor
            // This default value can be overridden with a system property
            final boolean isAshaEnabledByDefault =
                    !(isAutomotive(mContext) || isWatch(mContext) || isTv(mContext));
            mIsHearingAidProfileSupported =
                    BluetoothProperties.isProfileAshaCentralEnabled()
                            .orElse(isAshaEnabledByDefault);
        }

        String value = SystemProperties.get(
                "persist.sys.fflag.override.settings_bluetooth_hearing_aid");

        if (!TextUtils.isEmpty(value)) {
            boolean isHearingAidEnabled = Boolean.parseBoolean(value);
            Log.v(TAG, "set feature flag HEARING_AID_SETTINGS to " + isHearingAidEnabled);
            if (isHearingAidEnabled && !mIsHearingAidProfileSupported) {
                // Overwrite to enable support by FeatureFlag
                mIsHearingAidProfileSupported = true;
            }
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_BLUETOOTH_ADDRESS_CHANGED);
        filter.addAction(Intent.ACTION_SETTING_RESTORED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(Intent.ACTION_SHUTDOWN);
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        mContext.registerReceiver(mReceiver, filter);

        IntentFilter filterUser = new IntentFilter();
        filterUser.addAction(UserManager.ACTION_USER_RESTRICTIONS_CHANGED);
        filterUser.addAction(Intent.ACTION_USER_SWITCHED);
        filterUser.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        mContext.registerReceiverForAllUsers(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                switch (intent.getAction()) {
                    case Intent.ACTION_USER_SWITCHED:
                        int foregroundUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                        propagateForegroundUserId(foregroundUserId);
                        break;
                    case UserManager.ACTION_USER_RESTRICTIONS_CHANGED:
                        onUserRestrictionsChanged(getSendingUser());
                        break;
                    default:
                        Log.e(TAG, "Unknown broadcast received in BluetoothManagerService receiver"
                                + " registered across all users");
                }
            }
        }, filterUser, null, null);

        loadStoredNameAndAddress();
        if (isBluetoothPersistedStateOn()) {
            if (DBG) {
                Log.d(TAG, "Startup: Bluetooth persisted state is ON.");
            }
            mEnableExternal = true;
        }

        String airplaneModeRadios =
                Settings.Global.getString(mContentResolver, Settings.Global.AIRPLANE_MODE_RADIOS);
        if (airplaneModeRadios == null || airplaneModeRadios.contains(
                Settings.Global.RADIO_BLUETOOTH)) {
            mBluetoothAirplaneModeListener = new BluetoothAirplaneModeListener(
                    this, mBluetoothHandlerThread.getLooper(), context,
                    mBluetoothNotificationManager);
        }

        int systemUiUid = -1;
        // Check if device is configured with no home screen, which implies no SystemUI.
        try {
            systemUiUid = mContext.createContextAsUser(UserHandle.SYSTEM, 0)
                .getPackageManager()
                .getPackageUid("com.android.systemui",
                        PackageManager.PackageInfoFlags.of(PackageManager.MATCH_SYSTEM_ONLY));
            Log.d(TAG, "Detected SystemUiUid: " + Integer.toString(systemUiUid));
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Unable to resolve SystemUI's UID.");
        }
        mSystemUiUid = systemUiUid;

        mBluetoothSatelliteModeListener = new BluetoothSatelliteModeListener(
                this, mBluetoothHandlerThread.getLooper(), context);
    }

    /**
     *  Returns true if airplane mode is currently on
     */
    private boolean isAirplaneModeOn() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
    }

    /**
     * @hide constant copied from {@link Settings.Global}
     * TODO(b/274636414): Migrate to official API in Android V.
     */
    @VisibleForTesting
    static final String SETTINGS_SATELLITE_MODE_RADIOS = "satellite_mode_radios";
    /**
     * @hide constant copied from {@link Settings.Global}
     * TODO(b/274636414): Migrate to official API in Android V.
     */
    @VisibleForTesting
    static final String SETTINGS_SATELLITE_MODE_ENABLED = "satellite_mode_enabled";

    private boolean isSatelliteModeSensitive() {
        final String satelliteRadios = Settings.Global.getString(mContext.getContentResolver(),
                SETTINGS_SATELLITE_MODE_RADIOS);
        return satelliteRadios != null
                && satelliteRadios.contains(Settings.Global.RADIO_BLUETOOTH);
    }

    /** Returns true if satellite mode is turned on. */
    private boolean isSatelliteModeOn() {
        if (!isSatelliteModeSensitive()) return false;
        return Settings.Global.getInt(mContext.getContentResolver(),
                SETTINGS_SATELLITE_MODE_ENABLED, 0) == 1;
    }

    /**
     *  Returns true if airplane mode enhancement feature is enabled
     */
    private boolean isApmEnhancementOn() {
        return Settings.Global.getInt(mContext.getContentResolver(), APM_ENHANCEMENT, 0) == 1;
    }

    private boolean supportBluetoothPersistedState() {
        // Set default support to true to copy config default.
        return BluetoothProperties.isSupportPersistedStateEnabled().orElse(true);
    }

    /**
     *  Returns true if the Bluetooth saved state is "on"
     */
    private boolean isBluetoothPersistedStateOn() {
        if (!supportBluetoothPersistedState()) {
            return false;
        }
        int state = Settings.Global.getInt(mContentResolver, Settings.Global.BLUETOOTH_ON, -1);
        if (DBG) {
            Log.d(TAG, "Bluetooth persisted state: " + state);
        }
        return state != BLUETOOTH_OFF;
    }

    private boolean isBluetoothPersistedStateOnAirplane() {
        if (!supportBluetoothPersistedState()) {
            return false;
        }
        int state = Settings.Global.getInt(mContentResolver, Settings.Global.BLUETOOTH_ON, -1);
        if (DBG) {
            Log.d(TAG, "Bluetooth persisted state: " + state);
        }
        return state == BLUETOOTH_ON_AIRPLANE;
    }

    /**
     *  Returns true if the Bluetooth saved state is BLUETOOTH_ON_BLUETOOTH
     */
    private boolean isBluetoothPersistedStateOnBluetooth() {
        if (!supportBluetoothPersistedState()) {
            return false;
        }
        return Settings.Global.getInt(mContentResolver, Settings.Global.BLUETOOTH_ON,
                BLUETOOTH_ON_BLUETOOTH) == BLUETOOTH_ON_BLUETOOTH;
    }

    /**
     *  Save the Bluetooth on/off state
     */
    private void persistBluetoothSetting(int value) {
        if (DBG) {
            Log.d(TAG, "Persisting Bluetooth Setting: " + value);
        }
        // waive WRITE_SECURE_SETTINGS permission check
        final long callingIdentity = Binder.clearCallingIdentity();
        try {
            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.BLUETOOTH_ON, value);
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
    }

    /**
     *  Set the Settings Secure Int value for foreground user
     */
    private void setSettingsSecureInt(String name, int value) {
        if (DBG) {
            Log.d(TAG, "Persisting Settings Secure Int: " + name + "=" + value);
        }

        // waive WRITE_SECURE_SETTINGS permission check
        final long callingIdentity = Binder.clearCallingIdentity();
        try {
            Context userContext = mContext.createContextAsUser(
                    UserHandle.of(ActivityManager.getCurrentUser()), 0);
            Settings.Secure.putInt(userContext.getContentResolver(), name, value);
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
    }

    /**
     *  Return whether APM notification has been shown
     */
    private boolean isFirstTimeNotification(String name) {
        boolean firstTime = false;
        // waive WRITE_SECURE_SETTINGS permission check
        final long callingIdentity = Binder.clearCallingIdentity();
        try {
            Context userContext = mContext.createContextAsUser(
                    UserHandle.of(ActivityManager.getCurrentUser()), 0);
            firstTime = Settings.Secure.getInt(userContext.getContentResolver(), name,
                    NOTIFICATION_NOT_SHOWN) == NOTIFICATION_NOT_SHOWN;
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
        return firstTime;
    }

    /**
     * Returns true if the Bluetooth Adapter's name and address is
     * locally cached
     * @return
     */
    private boolean isNameAndAddressSet() {
        return mName != null && mAddress != null && mName.length() > 0 && mAddress.length() > 0;
    }

    /**
     * Retrieve the Bluetooth Adapter's name and address and save it in
     * in the local cache
     */
    private void loadStoredNameAndAddress() {
        if (DBG) {
            Log.d(TAG, "Loading stored name and address");
        }
        if (BluetoothProperties.isAdapterAddressValidationEnabled().orElse(false)
                && Settings.Secure.getInt(mContentResolver,
                    Settings.Secure.BLUETOOTH_ADDR_VALID, 0) == 0) {
            // if the valid flag is not set, don't load the address and name
            if (DBG) {
                Log.d(TAG, "invalid bluetooth name and address stored");
            }
            return;
        }
        mName = BluetoothServerProxy.getInstance()
                .settingsSecureGetString(mContentResolver, Settings.Secure.BLUETOOTH_NAME);
        mAddress = BluetoothServerProxy.getInstance()
                .settingsSecureGetString(mContentResolver, Settings.Secure.BLUETOOTH_ADDRESS);

        if (DBG) {
            Log.d(TAG, "Stored bluetooth Name=" + mName + ",Address=" + mAddress);
        }
    }

    /**
     * Save the Bluetooth name and address in the persistent store.
     * Only non-null values will be saved.
     * @param name
     * @param address
     */
    private void storeNameAndAddress(String name, String address) {
        if (name != null) {
            Settings.Secure.putString(mContentResolver, Settings.Secure.BLUETOOTH_NAME, name);
            mName = name;
            if (DBG) {
                Log.d(TAG, "Stored Bluetooth name: " + Settings.Secure.getString(mContentResolver,
                            Settings.Secure.BLUETOOTH_NAME));
            }
        }

        if (address != null) {
            Settings.Secure.putString(mContentResolver, Settings.Secure.BLUETOOTH_ADDRESS, address);
            mAddress = address;
            if (DBG) {
                Log.d(TAG, "Stored Bluetoothaddress: " + Settings.Secure.getString(mContentResolver,
                            Settings.Secure.BLUETOOTH_ADDRESS));
            }
        }

        if ((name != null) && (address != null)) {
            Settings.Secure.putInt(mContentResolver, Settings.Secure.BLUETOOTH_ADDR_VALID, 1);
        }
    }

    public IBluetooth registerAdapter(IBluetoothManagerCallback callback) {
        if (callback == null) {
            Log.w(TAG, "Callback is null in registerAdapter");
            return null;
        }
        synchronized (mCallbacks) {
            mCallbacks.register(callback);
        }
        return mBluetooth;
    }

    public void unregisterAdapter(IBluetoothManagerCallback callback) {
        if (callback == null) {
            Log.w(TAG, "Callback is null in unregisterAdapter");
            return;
        }
        synchronized (mCallbacks) {
            mCallbacks.unregister(callback);
        }
    }

    public void registerStateChangeCallback(IBluetoothStateChangeCallback callback) {
        if (callback == null) {
            Log.w(TAG, "registerStateChangeCallback: Callback is null!");
            return;
        }
        Message msg = mHandler.obtainMessage(MESSAGE_REGISTER_STATE_CHANGE_CALLBACK);
        msg.obj = callback;
        mHandler.sendMessage(msg);
    }

    public void unregisterStateChangeCallback(IBluetoothStateChangeCallback callback) {
        if (callback == null) {
            Log.w(TAG, "unregisterStateChangeCallback: Callback is null!");
            return;
        }
        Message msg = mHandler.obtainMessage(MESSAGE_UNREGISTER_STATE_CHANGE_CALLBACK);
        msg.obj = callback;
        mHandler.sendMessage(msg);
    }

    public boolean isEnabled() {
        return getState() == BluetoothAdapter.STATE_ON;
    }

    @GuardedBy("mBluetoothLock")
    private boolean synchronousDisable(AttributionSource attributionSource)
            throws RemoteException, TimeoutException {
        if (mBluetooth == null) return false;
        final SynchronousResultReceiver<Boolean> recv = SynchronousResultReceiver.get();
        mBluetooth.disable(attributionSource, recv);
        return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(false);
    }

    @GuardedBy("mBluetoothLock")
    private boolean synchronousEnable(boolean quietMode, AttributionSource attributionSource)
            throws RemoteException, TimeoutException {
        if (mBluetooth == null) return false;
        final SynchronousResultReceiver<Boolean> recv = SynchronousResultReceiver.get();
        mBluetooth.enable(quietMode, attributionSource, recv);
        return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(false);
    }

    @GuardedBy("mBluetoothLock")
    private String synchronousGetAddress(AttributionSource attributionSource)
            throws RemoteException, TimeoutException {
        if (mBluetooth == null) return null;
        final SynchronousResultReceiver<String> recv = SynchronousResultReceiver.get();
        mBluetooth.getAddress(attributionSource, recv);
        return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(null);
    }

    @GuardedBy("mBluetoothLock")
    private String synchronousGetName(AttributionSource attributionSource)
            throws RemoteException, TimeoutException {
        if (mBluetooth == null) return null;
        final SynchronousResultReceiver<String> recv = SynchronousResultReceiver.get();
        mBluetooth.getName(attributionSource, recv);
        return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(null);
    }

    @GuardedBy("mBluetoothLock")
    private int synchronousGetState()
            throws RemoteException, TimeoutException {
        if (mBluetooth == null) return BluetoothAdapter.STATE_OFF;
        final SynchronousResultReceiver<Integer> recv = SynchronousResultReceiver.get();
        mBluetooth.getState(recv);
        return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(BluetoothAdapter.STATE_OFF);
    }

    @GuardedBy("mBluetoothLock")
    private void synchronousOnBrEdrDown(AttributionSource attributionSource)
            throws RemoteException, TimeoutException {
        if (mBluetooth == null) return;
        final SynchronousResultReceiver recv = SynchronousResultReceiver.get();
        mBluetooth.onBrEdrDown(attributionSource, recv);
        recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(null);
    }

    @GuardedBy("mBluetoothLock")
    private void synchronousOnLeServiceUp(AttributionSource attributionSource)
            throws RemoteException, TimeoutException {
        if (mBluetooth == null) return;
        final SynchronousResultReceiver recv = SynchronousResultReceiver.get();
        mBluetooth.onLeServiceUp(attributionSource, recv);
        recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(null);
    }

    @GuardedBy("mBluetoothLock")
    private void synchronousRegisterCallback(IBluetoothCallback callback,
            AttributionSource attributionSource) throws RemoteException, TimeoutException {
        if (mBluetooth == null) return;
        final SynchronousResultReceiver recv = SynchronousResultReceiver.get();
        mBluetooth.registerCallback(callback, attributionSource, recv);
        recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(null);
    }

    @GuardedBy("mBluetoothLock")
    private void synchronousUnregisterCallback(IBluetoothCallback callback,
            AttributionSource attributionSource) throws RemoteException, TimeoutException {
        if (mBluetooth == null) return;
        final SynchronousResultReceiver recv = SynchronousResultReceiver.get();
        mBluetooth.unregisterCallback(callback, attributionSource, recv);
        recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(null);
    }

    @GuardedBy("mBluetoothLock")
    private List<Integer> synchronousGetSupportedProfiles(AttributionSource attributionSource)
            throws RemoteException, TimeoutException {
        final ArrayList<Integer> supportedProfiles = new ArrayList<Integer>();
        if (mBluetooth == null) return supportedProfiles;
        final SynchronousResultReceiver<Long> recv = SynchronousResultReceiver.get();
        mBluetooth.getSupportedProfiles(attributionSource, recv);
        final long supportedProfilesBitMask =
                recv.awaitResultNoInterrupt(getSyncTimeout()).getValue((long) 0);

        for (int i = 0; i <= BluetoothProfile.MAX_PROFILE_ID; i++) {
            if ((supportedProfilesBitMask & (1L << i)) != 0) {
                supportedProfiles.add(i);
            }
        }

        return supportedProfiles;
    }

    /**
     * Sends the current foreground user id to the Bluetooth process. This user id is used to
     * determine if Binder calls are coming from the active user.
     *
     * @param userId is the foreground user id we are propagating to the Bluetooth process
     */
    private void propagateForegroundUserId(int userId) {
        mBluetoothLock.readLock().lock();
        try {
            if (mBluetooth != null) {
                mBluetooth.setForegroundUserId(userId, mContext.getAttributionSource());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to set foreground user id", e);
        } finally {
            mBluetoothLock.readLock().unlock();
        }
    }

    public int getState() {
        if (!isCallerSystem(getCallingAppId()) && !checkIfCallerIsForegroundUser()) {
            Log.w(TAG, "getState(): report OFF for non-active and non system user");
            return BluetoothAdapter.STATE_OFF;
        }

        mBluetoothLock.readLock().lock();
        try {
            if (mBluetooth != null) {
                return synchronousGetState();
            }
        } catch (RemoteException | TimeoutException e) {
            Log.e(TAG, "getState()", e);
        } finally {
            mBluetoothLock.readLock().unlock();
        }
        return BluetoothAdapter.STATE_OFF;
    }

    class ClientDeathRecipient implements IBinder.DeathRecipient {
        private String mPackageName;

        ClientDeathRecipient(String packageName) {
            mPackageName = packageName;
        }

        public void binderDied() {
            if (DBG) {
                Log.d(TAG, "Binder is dead - unregister " + mPackageName);
            }

            for (Map.Entry<IBinder, ClientDeathRecipient> entry : mBleApps.entrySet()) {
                IBinder token = entry.getKey();
                ClientDeathRecipient deathRec = entry.getValue();
                if (deathRec.equals(this)) {
                    updateBleAppCount(token, false, mPackageName);
                    break;
                }
            }

            int appCount = mBleApps.size();
            if (DBG) {
                Log.d(TAG, appCount + "Binder is dead,registered Ble Apps");
            }

            if (appCount == 0 && mEnable) {
                disableBleScanMode();
            }

            if (appCount == 0) {
                int st = BluetoothAdapter.STATE_OFF;
                try {
                    mBluetoothLock.readLock().lock();
                    if (mBluetooth != null) {
                        st = getState();
                    }
                    if (!mEnableExternal || (st == BluetoothAdapter.STATE_BLE_ON)) {
                        if (DBG) {
                            Log.d(TAG, "Move to BT state OFF");
                        }
                        sendBrEdrDownCallback(mContext.getAttributionSource());
                    }
                } finally {
                    mBluetoothLock.readLock().unlock();
                }
            }
        }

        public String getPackageName() {
            return mPackageName;
        }
    }

    @Override
    public boolean isBleScanAlwaysAvailable() {
        if (isAirplaneModeOn() && !mEnable) {
            return false;
        }
        try {
            return Settings.Global.getInt(mContentResolver,
                    Settings.Global.BLE_SCAN_ALWAYS_AVAILABLE) != 0;
        } catch (SettingNotFoundException e) {
        }
        return false;
    }

    @Override
    public boolean isHearingAidProfileSupported() {
        return mIsHearingAidProfileSupported;
    }

    private boolean isDeviceProvisioned() {
        return Settings.Global.getInt(mContentResolver, Settings.Global.DEVICE_PROVISIONED,
                0) != 0;
    }

    // Monitor change of BLE scan only mode settings.
    private void registerForProvisioningStateChange() {
        ContentObserver contentObserver = new ContentObserver(null) {
            @Override
            public void onChange(boolean selfChange) {
                if (!isDeviceProvisioned()) {
                    if (DBG) {
                        Log.d(TAG, "DEVICE_PROVISIONED setting changed, but device is not "
                                + "provisioned");
                    }
                    return;
                }
                if (mHandler.hasMessages(MESSAGE_INIT_FLAGS_CHANGED)) {
                    Log.i(TAG, "Device provisioned, reactivating pending flag changes");
                    onInitFlagsChanged();
                }
            }
        };

        mContentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.DEVICE_PROVISIONED), false,
                contentObserver);
    }

    // Monitor change of BLE scan only mode settings.
    private void registerForBleScanModeChange() {
        ContentObserver contentObserver = new ContentObserver(null) {
            @Override
            public void onChange(boolean selfChange) {
                if (isBleScanAlwaysAvailable()) {
                    // Nothing to do
                    return;
                }
                // BLE scan is not available.
                disableBleScanMode();
                clearBleApps();
                mBluetoothLock.readLock().lock();
                try {
                    if (mBluetooth != null) {
                        addActiveLog(BluetoothProtoEnums.ENABLE_DISABLE_REASON_APPLICATION_REQUEST,
                                mContext.getPackageName(), false);
                        synchronousOnBrEdrDown(mContext.getAttributionSource());
                    }
                } catch (RemoteException | TimeoutException e) {
                    Log.e(TAG, "error when disabling bluetooth", e);
                } finally {
                    mBluetoothLock.readLock().unlock();
                }
            }
        };

        mContentResolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.BLE_SCAN_ALWAYS_AVAILABLE), false, contentObserver);
    }

    // Disable ble scan only mode.
    private void disableBleScanMode() {
        try {
            mBluetoothLock.readLock().lock();
            if (mBluetooth != null && (synchronousGetState() != BluetoothAdapter.STATE_ON) && (!isBluetoothPersistedStateOnBluetooth())) {
                if (DBG) {
                    Log.d(TAG, "Resetting the mEnable flag for clean disable");
                }
                if (!mEnableExternal) {
                    mEnable = false;
                }
            }
        } catch (RemoteException | TimeoutException e) {
            Log.e(TAG, "getState()", e);
        } finally {
            mBluetoothLock.readLock().unlock();
        }
    }

    private int updateBleAppCount(IBinder token, boolean enable, String packageName) {
        ClientDeathRecipient r = mBleApps.get(token);
        int st = BluetoothAdapter.STATE_OFF;
        if (r == null && enable) {
            ClientDeathRecipient deathRec = new ClientDeathRecipient(packageName);
            try {
                token.linkToDeath(deathRec, 0);
            } catch (RemoteException ex) {
                throw new IllegalArgumentException("BLE app (" + packageName + ") already dead!");
            }
            mBleApps.put(token, deathRec);
            if (DBG) {
                Log.d(TAG, "Registered for death of " + packageName);
            }
        } else if (!enable && r != null) {
            // Unregister death recipient as the app goes away.
            token.unlinkToDeath(r, 0);
            mBleApps.remove(token);
            if (DBG) {
                Log.d(TAG, "Unregistered for death of " + packageName);
            }
        }

        int appCount = mBleApps.size();
        if (DBG) {
            Log.d(TAG, appCount + " registered Ble Apps");
        }
        return appCount;
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    private boolean checkBluetoothPermissions(AttributionSource attributionSource, String message,
            boolean requireForeground) {
        if (isBluetoothDisallowed()) {
            if (DBG) {
                Log.d(TAG, "checkBluetoothPermissions: bluetooth disallowed");
            }
            return false;
        }
        int callingAppId = getCallingAppId();
        if (!isCallerSystem(callingAppId)
                && !isCallerShell(callingAppId)
                && !isCallerRoot(callingAppId)) {
            checkPackage(attributionSource.getPackageName());

            if (requireForeground && !checkIfCallerIsForegroundUser()) {
                Log.w(TAG, "Not allowed for non-active and non system user");
                return false;
            }

            if (!checkConnectPermissionForDataDelivery(mContext, attributionSource, message)) {
                return false;
            }
        }
        return true;
    }

    public boolean enableBle(AttributionSource attributionSource, IBinder token)
            throws RemoteException {
        final String packageName = attributionSource.getPackageName();
        if (!checkBluetoothPermissions(attributionSource, "enableBle", false)
                || isAirplaneModeOn()) {
            if (DBG) {
                Log.d(TAG, "enableBle(): bluetooth disallowed");
            }
            return false;
        }

        if (DBG) {
            Log.d(TAG, "enableBle(" + packageName + "):  mBluetooth =" + mBluetooth
                    + " mBinding = " + mBinding + " mState = "
                    + BluetoothAdapter.nameForState(mState));
        }

        if (isSatelliteModeOn()) {
            Log.d(TAG, "enableBle(): not enabling - satellite mode is on.");
            return false;
        }

        updateBleAppCount(token, true, packageName);

        if (mState == BluetoothAdapter.STATE_ON
                || mState == BluetoothAdapter.STATE_BLE_ON
                || mState == BluetoothAdapter.STATE_TURNING_ON
                || mState == BluetoothAdapter.STATE_TURNING_OFF
                || mState == BluetoothAdapter.STATE_BLE_TURNING_ON) {
            Log.d(TAG, "enableBLE(): Bluetooth is already enabled or is turning on");
            return true;
        }
        synchronized (mReceiver) {
            // waive WRITE_SECURE_SETTINGS permission check
            sendEnableMsg(false, BluetoothProtoEnums.ENABLE_DISABLE_REASON_APPLICATION_REQUEST,
                    packageName, true);
        }
        return true;
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    public boolean disableBle(AttributionSource attributionSource, IBinder token)
            throws RemoteException {
        final String packageName = attributionSource.getPackageName();
        if (!checkBluetoothPermissions(attributionSource, "disableBle", false)) {
            if (DBG) {
                Log.d(TAG, "disableBLE(): bluetooth disallowed");
            }
            return false;
        }

        if (DBG) {
            Log.d(TAG, "disableBle(" + packageName + "):  mBluetooth =" + mBluetooth
                    + " mBinding = " + mBinding + " mState = "
                    + BluetoothAdapter.nameForState(mState));
        }
        /* update app count even in bt off state, if quick enableBle and
         * disableBle during BT turning off may leave app count non zero
         */
        updateBleAppCount(token, false, packageName);

        if (isSatelliteModeOn()) {
            Log.d(TAG, "disableBle(): not disabling - satellite mode is on.");
            return false;
        }

        if (mState == BluetoothAdapter.STATE_OFF) {
            Log.d(TAG, "disableBLE(): Already disabled");
            return false;
        }

        if (mState == BluetoothAdapter.STATE_BLE_ON && !isBleAppPresent()) {
            if (mEnable) {
                disableBleScanMode();
            }
            if (!mEnableExternal) {
                addActiveLog(BluetoothProtoEnums.ENABLE_DISABLE_REASON_APPLICATION_REQUEST,
                        packageName, false);
                sendBrEdrDownCallback(attributionSource);
            }
        }
        return true;
    }

    // Clear all apps using BLE scan only mode.
    private void clearBleApps() {
        mBleApps.clear();
    }

    /** @hide */
    public boolean isBleAppPresent() {
        if (DBG) {
            Log.d(TAG, "isBleAppPresent() count: " + mBleApps.size());
        }
        return mBleApps.size() > 0;
    }

    /**
     * Call IBluetooth.onLeServiceUp() to continue if Bluetooth should be on,
     * call IBluetooth.onBrEdrDown() to disable if Bluetooth should be off.
     */
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    private void continueFromBleOnState() {
        if (DBG) {
            Log.d(TAG, "continueFromBleOnState()");
        }
        mBluetoothLock.readLock().lock();
        try {
            if (mBluetooth == null) {
                Log.e(TAG, "onBluetoothServiceUp: mBluetooth is null!");
                return;
            }
            int st = getState();
            if (st != BluetoothAdapter.STATE_BLE_ON) {
                if (DBG) Log.v(TAG, "onBluetoothServiceUp: state isn't BLE_ON: " +
                         BluetoothAdapter.nameForState(st));
                return;
            }
            if (!mEnableExternal && !isBleAppPresent() &&
                !isBluetoothPersistedStateOnBluetooth()) {
                Log.i(TAG, "Bluetooth was disabled while enabling BLE, disable BLE now");
                mEnable = false;
                synchronousOnBrEdrDown(mContext.getAttributionSource());
                return;
            }
            if (isBluetoothPersistedStateOnBluetooth() ||
                 mEnableExternal) {
                // This triggers transition to STATE_ON
                mBluetooth.updateQuietModeStatus(mQuietEnable,
                        mContext.getAttributionSource());
                synchronousOnLeServiceUp(mContext.getAttributionSource());
                persistBluetoothSetting(BLUETOOTH_ON_BLUETOOTH);
            }
        } catch (RemoteException | TimeoutException e) {
            Log.e(TAG, "Unable to call onServiceUp", e);
        } finally {
            mBluetoothLock.readLock().unlock();
        }
    }

    /**
     * Inform BluetoothAdapter instances that BREDR part is down
     * and turn off all service and stack if no LE app needs it
     */
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
    })
    private void sendBrEdrDownCallback(AttributionSource attributionSource) {
        if (DBG) {
            Log.d(TAG, "Calling sendBrEdrDownCallback callbacks");
        }

        if (mBluetooth == null) {
            Log.w(TAG, "Bluetooth handle is null");
            return;
        }

        if (isBleAppPresent()) {
            // Need to stay at BLE ON. Disconnect all Gatt connections
            try {
                final SynchronousResultReceiver recv = SynchronousResultReceiver.get();
                if (mBluetoothGatt != null) {
                    mBluetoothGatt.unregAll(attributionSource, recv);
                }
                recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(null);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, "Unable to disconnect all apps.", e);
            }
        } else {
            mBluetoothLock.readLock().lock();
            try {
                if (mBluetooth != null) {
                    synchronousOnBrEdrDown(attributionSource);
                }
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, "Call to onBrEdrDown() failed.", e);
            } finally {
                mBluetoothLock.readLock().unlock();
            }
        }

    }

    public boolean enableNoAutoConnect(AttributionSource attributionSource) {
        final String packageName = attributionSource.getPackageName();
        if (!checkBluetoothPermissions(attributionSource, "enableNoAutoConnect", false)) {
            if (DBG) {
                Log.d(TAG, "enableNoAutoConnect(): not enabling - bluetooth disallowed");
            }
            return false;
        }

        if (DBG) {
            Log.d(TAG, "enableNoAutoConnect():  mBluetooth =" + mBluetooth + " mBinding = "
                    + mBinding);
        }

        int callingAppId = UserHandle.getAppId(Binder.getCallingUid());
        if (callingAppId != Process.NFC_UID) {
            throw new SecurityException("no permission to enable Bluetooth quietly");
        }

        if (isSatelliteModeOn()) {
            Log.d(TAG, "enableNoAutoConnect(): not enabling - satellite mode is on.");
            return false;
        }

        synchronized (mReceiver) {
            mQuietEnableExternal = true;
            mEnableExternal = true;
            sendEnableMsg(true,
                    BluetoothProtoEnums.ENABLE_DISABLE_REASON_APPLICATION_REQUEST, packageName);
        }
        return true;
    }

    public boolean enable(AttributionSource attributionSource) throws RemoteException {
        final String packageName = attributionSource.getPackageName();
        if (!checkBluetoothPermissions(attributionSource, "enable", true)) {
            if (DBG) {
                Log.d(TAG, "enable(): not enabling - bluetooth disallowed");
            }
            return false;
        }

        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        if (CompatChanges.isChangeEnabled(RESTRICT_ENABLE_DISABLE, callingUid)
                && !isPrivileged(callingPid, callingUid)
                && !isSystem(packageName, callingUid)
                && !isDeviceOwner(callingUid, packageName)
                && !isProfileOwner(callingUid, packageName)) {
            Log.d(TAG, "enable(): not enabling - Caller is not one of: "
                    + "privileged | system | deviceOwner | profileOwner");
            return false;
        }

        if (isSatelliteModeOn()) {
            Log.d(TAG, "enable(): not enabling - satellite mode is on.");
            return false;
        }

        if (DBG) {
            Log.d(TAG, "enable(" + packageName + "):  mBluetooth=" + mBluetooth + " mBinding="
                    + mBinding + " mState=" + BluetoothAdapter.nameForState(mState));
        }

        synchronized (mReceiver) {
            mQuietEnableExternal = false;
            mEnableExternal = true;
            if (isAirplaneModeOn()) {
                mBluetoothAirplaneModeListener.updateBluetoothToggledTime();
                if (isApmEnhancementOn()) {
                    setSettingsSecureInt(BLUETOOTH_APM_STATE, BLUETOOTH_ON_APM);
                    setSettingsSecureInt(APM_USER_TOGGLED_BLUETOOTH, USED);
                    if (isFirstTimeNotification(APM_BT_ENABLED_NOTIFICATION)) {
                        final long callingIdentity = Binder.clearCallingIdentity();
                        try {
                            mBluetoothAirplaneModeListener.sendApmNotification(
                                    "bluetooth_enabled_apm_title",
                                    "bluetooth_enabled_apm_message",
                                    APM_BT_ENABLED_NOTIFICATION);
                        } catch (Exception e) {
                            Log.e(TAG, "APM enhancement BT enabled notification not shown");
                        } finally {
                            Binder.restoreCallingIdentity(callingIdentity);
                        }
                    }
                }
            }
            // waive WRITE_SECURE_SETTINGS permission check
            sendEnableMsg(false,
                    BluetoothProtoEnums.ENABLE_DISABLE_REASON_APPLICATION_REQUEST, packageName);
        }
        if (DBG) {
            Log.d(TAG, "enable returning");
        }
        return true;
    }

    public boolean disable(AttributionSource attributionSource, boolean persist)
            throws RemoteException {
        if (!persist) {
            mContext.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED,
                    "Need BLUETOOTH_PRIVILEGED permission");
        }

        final String packageName = attributionSource.getPackageName();
        if (!checkBluetoothPermissions(attributionSource, "disable", true)) {
            if (DBG) {
                Log.d(TAG, "disable(): not disabling - bluetooth disallowed");
            }
            return false;
        }

        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        if (CompatChanges.isChangeEnabled(RESTRICT_ENABLE_DISABLE, callingUid)
                && !isPrivileged(callingPid, callingUid)
                && !isSystem(packageName, callingUid)
                && !isDeviceOwner(callingUid, packageName)
                && !isProfileOwner(callingUid, packageName)) {
            Log.d(TAG, "disable(): not disabling - Caller is not one of: "
                    + "privileged | system | deviceOwner | profileOwner");
            return false;
        }

        if (isSatelliteModeOn()) {
            Log.d(TAG, "disable: not disabling - satellite mode is on.");
            return false;
        }

        if (DBG) {
            Log.d(TAG, "disable(" + packageName + "): mBluetooth = "
                    + mBluetooth + ", persist=" + persist
                    + ", mBinding = " + mBinding);
        }

        synchronized (mReceiver) {
            if (isAirplaneModeOn()) {
                mBluetoothAirplaneModeListener.updateBluetoothToggledTime();
                if (isApmEnhancementOn()) {
                    setSettingsSecureInt(BLUETOOTH_APM_STATE, BLUETOOTH_OFF_APM);
                    setSettingsSecureInt(APM_USER_TOGGLED_BLUETOOTH, USED);
                }
            }
            if (persist) {
                sendDisableMsg(BluetoothProtoEnums.ENABLE_DISABLE_REASON_APPLICATION_REQUEST,
                        packageName);
            } else {
                /* It means disable is called by shutdown thread */
                synchronized (this) {
                    clearBleApps();
                }

                try {
                    mBluetoothLock.readLock().lock();
                    mEnableExternal = false;
                    if (mBluetooth != null) {
                        if(getState() == BluetoothAdapter.STATE_BLE_ON) {
                            mEnable = false;
                            synchronousOnBrEdrDown(attributionSource);
                        } else {
                            sendDisableMsg(BluetoothProtoEnums.ENABLE_DISABLE_REASON_SYSTEM_BOOT,
                                    packageName);
                        }
                    }
                } catch (RemoteException | TimeoutException e) {
                    Log.e(TAG, "Unable to initiate disable", e);
                } finally {
                    mBluetoothLock.readLock().unlock();
                }
            }

            if (persist) {
                persistBluetoothSetting(BLUETOOTH_OFF);
            }
            mEnableExternal = false;
        }
        return true;
    }

    /**
     * Check if AppOpsManager is available and the packageName belongs to calling uid
     *
     * A null package belongs to any uid
     */
    private void checkPackage(String packageName) {
        int callingUid = Binder.getCallingUid();

        if (mAppOps == null) {
            Log.w(TAG, "checkPackage(): called before system boot up, uid "
                    + callingUid + ", packageName " + packageName);
            throw new IllegalStateException("System has not boot yet");
        }
        if (packageName == null) {
            Log.w(TAG, "checkPackage(): called with null packageName from " + callingUid);
            return;
        }

        try {
            mAppOps.checkPackage(callingUid, packageName);
        } catch (SecurityException e) {
            Log.w(TAG, "checkPackage(): " + packageName + " does not belong to uid " + callingUid);
            throw new SecurityException(e.getMessage());
        }
    }

    /**
     * Check if the caller must still pass permission check or if the caller is exempted
     * from the consent UI via the MANAGE_BLUETOOTH_WHEN_WIRELESS_CONSENT_REQUIRED check.
     *
     * Commands from some callers may be exempted from triggering the consent UI when
     * enabling bluetooth. This exemption is checked via the
     * MANAGE_BLUETOOTH_WHEN_WIRELESS_CONSENT_REQUIRED and allows calls to skip
     * the consent UI where it may otherwise be required.
     *
     * @hide
     */
    @SuppressLint("AndroidFrameworkRequiresPermission")
    private boolean checkBluetoothPermissionWhenWirelessConsentRequired() {
        int result = mContext.checkCallingPermission(
                android.Manifest.permission.MANAGE_BLUETOOTH_WHEN_WIRELESS_CONSENT_REQUIRED);
        return result == PackageManager.PERMISSION_GRANTED;
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    public void unbindAndFinish() {
        if (DBG) {
            Log.d(TAG, "unbindAndFinish(): " + mBluetooth + " mBinding = " + mBinding
                    + " mUnbinding = " + mUnbinding);
        }

        mBluetoothLock.writeLock().lock();
        try {
            if (mUnbinding) {
                return;
            }
            mUnbinding = true;
            mHandler.removeMessages(MESSAGE_BLUETOOTH_STATE_CHANGE);
            mHandler.removeMessages(MESSAGE_BIND_PROFILE_SERVICE);
            if (mBluetooth != null) {
                //Unregister callback object
                try {
                    synchronousUnregisterCallback(mBluetoothCallback,
                            mContext.getAttributionSource());
                } catch (RemoteException e) {
                    Log.e(TAG, "Remote excp:Unable to unregister BluetoothCallback", e);
                }
                 catch (TimeoutException e) {
                    Log.e(TAG, "timeout excp:Unable to unregister BluetoothCallback", e);
                    Message timeoutMsg = mHandler.obtainMessage(MESSAGE_TIMEOUT_UNBIND);
                    mHandler.sendMessage(timeoutMsg);
                    mUnbinding = false;
                    return;
                }
                mBluetoothBinder = null;
                mBluetooth = null;
                mContext.unbindService(mConnection);
                mUnbinding = false;
                mBinding = false;
                mTryBindOnBindTimeout = false;
            } else {
                mUnbinding = false;
            }
            mBluetoothGatt = null;
        } finally {
            mBluetoothLock.writeLock().unlock();
        }
    }

    public IBluetoothGatt getBluetoothGatt() {
        // sync protection
        return mBluetoothGatt;
    }

    public boolean isBluetoothAvailableForBinding() {
        try {
            mBluetoothLock.readLock().lock();
            int state = getState();
            if (mBluetooth != null && ((state == BluetoothAdapter.STATE_ON) ||
                (state == BluetoothAdapter.STATE_TURNING_ON))) {
                return true;
            } else {
                return false;
            }
        } finally {
            mBluetoothLock.readLock().unlock();
        }
    }

    @Override
    public boolean bindBluetoothProfileService(int bluetoothProfile, String serviceName,
            IBluetoothProfileServiceConnection proxy) {
        if (isBluetoothAvailableForBinding() == false) {
            Log.w(TAG, "bindBluetoothProfileService:Trying to bind to profile: "
                       + bluetoothProfile + ", while Bluetooth is disabled");
            return false;
        }
        ProfileServiceConnections psc = null;
        synchronized (mProfileServices) {
            if (!mSupportedProfileList.contains(bluetoothProfile)) {
                Log.w(TAG, "Cannot bind profile: "  + bluetoothProfile
                        + ", not in supported profiles list");
                return false;
            }
            if (mProfileServices.get(Integer.valueOf(bluetoothProfile)) == null) {
                if (DBG) {
                    Log.d(TAG, "Creating new ProfileServiceConnections object for" + " profile: "
                            + bluetoothProfile);
                }
                psc = new ProfileServiceConnections(new Intent(serviceName));
                mProfileServices.put(new Integer(bluetoothProfile), psc);
            }
            else
               Log.w(TAG, "psc is not null in bindBluetoothProfileService");
        }
        if (psc != null) {
            if (!psc.bindService(DEFAULT_REBIND_COUNT)) {
                synchronized (mProfileServices) {
                     mProfileServices.remove(new Integer(bluetoothProfile));
                }
                return false;
            }
        }
        // Introducing a delay to give the client app time to prepare
        Message addProxyMsg = mHandler.obtainMessage(MESSAGE_ADD_PROXY_DELAYED);
        addProxyMsg.arg1 = bluetoothProfile;
        addProxyMsg.obj = proxy;
        mHandler.sendMessageDelayed(addProxyMsg, ADD_PROXY_DELAY_MS);
        return true;
    }

    @Override
    public void unbindBluetoothProfileService(int bluetoothProfile,
            IBluetoothProfileServiceConnection proxy) {
        synchronized (mProfileServices) {
            Integer profile = new Integer(bluetoothProfile);
            ProfileServiceConnections psc = mProfileServices.get(profile);
            if (psc == null) {
                Log.e(TAG, "unbindBluetoothProfileService: psc is null, returning");
                return;
            }
            Log.w(TAG, "unbindBluetoothProfileService: calling psc.removeProxy");
            psc.removeProxy(proxy);
            if (psc.isEmpty()) {
                // All proxies are disconnected, unbind with the service.
                try {
                    mContext.unbindService(psc);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Unable to unbind service with intent: " + psc.mIntent, e);
                }
                if (!mUnbindingAll) {
                    Log.w(TAG, "psc.isEmpty is true, removing psc entry for profile "
                                 + profile);
                    mProfileServices.remove(profile);
                }
            }
        }
    }

    private void unbindAllBluetoothProfileServices() {
        synchronized (mProfileServices) {
            mUnbindingAll = true;
            for (Integer i : mProfileServices.keySet()) {
                ProfileServiceConnections psc = mProfileServices.get(i);
                try {
                    mContext.unbindService(psc);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Unable to unbind service with intent: " + psc.mIntent, e);
                }
                psc.removeAllProxies();
            }
            mUnbindingAll = false;
            mProfileServices.clear();
        }
    }

    /**
     * Send enable message and set adapter name and address. Called when the boot phase becomes
     * PHASE_SYSTEM_SERVICES_READY.
     */
    public void handleOnBootPhase() {
        if (DBG) {
            Log.d(TAG, "Bluetooth boot completed");
        }
        mAppOps = mContext.getSystemService(AppOpsManager.class);
        final boolean isBluetoothDisallowed = isBluetoothDisallowed();
        if (isBluetoothDisallowed) {
            return;
        }
        final boolean isSafeMode = mContext.getPackageManager().isSafeMode();
        if (mEnableExternal && isBluetoothPersistedStateOnBluetooth() && !isSafeMode) {
            if (DBG) {
                Log.d(TAG, "Auto-enabling Bluetooth.");
            }
            sendEnableMsg(mQuietEnableExternal,
                    BluetoothProtoEnums.ENABLE_DISABLE_REASON_SYSTEM_BOOT,
                    mContext.getPackageName());
        } else if (!isNameAndAddressSet()) {
            if (DBG) {
                Log.d(TAG, "Getting adapter name and address");
            }
            Message getMsg = mHandler.obtainMessage(MESSAGE_GET_NAME_AND_ADDRESS);
            mHandler.sendMessage(getMsg);
        }

        mBluetoothModeChangeHelper = new BluetoothModeChangeHelper(mContext);
        if (mBluetoothAirplaneModeListener != null) {
            mBluetoothAirplaneModeListener.start(mBluetoothModeChangeHelper);
        }
        registerForProvisioningStateChange();
        mBluetoothDeviceConfigListener = new BluetoothDeviceConfigListener(this, DBG);
        setApmEnhancementState();
    }

    /** set APM enhancement feature state */
    @VisibleForTesting
    void setApmEnhancementState() {
        Settings.Global.putInt(
                mContext.getContentResolver(), APM_ENHANCEMENT, DEFAULT_APM_ENHANCEMENT_STATE);
    }

    /**
     * Called when switching to a different foreground user.
     */
    void handleSwitchUser(UserHandle userHandle) {
        if (DBG) {
            Log.d(TAG, "User " + userHandle + " switched");
        }
        mHandler.obtainMessage(MESSAGE_USER_SWITCHED, userHandle.getIdentifier(), 0).sendToTarget();
    }

    /**
     * Called when user is unlocked.
     */
    public void handleOnUnlockUser(UserHandle userHandle) {
        if (DBG) {
            Log.d(TAG, "User " + userHandle + " unlocked");
        }
        mHandler.obtainMessage(MESSAGE_USER_UNLOCKED, userHandle.getIdentifier(), 0).sendToTarget();
    }

    /**
     * This class manages the clients connected to a given ProfileService
     * and maintains the connection with that service.
     */
    private final class ProfileServiceConnections
            implements ServiceConnection, IBinder.DeathRecipient {
        final RemoteCallbackList<IBluetoothProfileServiceConnection> mProxies =
                new RemoteCallbackList<IBluetoothProfileServiceConnection>();
        IBinder mService;
        ComponentName mClassName;
        Intent mIntent;

        ProfileServiceConnections(Intent intent) {
            mService = null;
            mClassName = null;
            mIntent = intent;
        }

        private boolean bindService(int rebindCount) {
            int state = BluetoothAdapter.STATE_OFF;
            try {
                mBluetoothLock.readLock().lock();
                state = synchronousGetState();
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, "Unable to call getState", e);
                return false;
            } finally {
                mBluetoothLock.readLock().unlock();
            }

            if (state != BluetoothAdapter.STATE_ON) {
                if (DBG) {
                    Log.d(TAG, "Unable to bindService while Bluetooth is disabled");
                }
                return false;
            }

            if (mIntent != null && mService == null
                    && doBind(mIntent, this, 0, USER_HANDLE_CURRENT_OR_SELF)) {
                Message msg = mHandler.obtainMessage(MESSAGE_BIND_PROFILE_SERVICE);
                msg.obj = this;
                msg.arg1 = rebindCount;
                mHandler.sendMessageDelayed(msg, TIMEOUT_BIND_MS);
                return true;
            }
            Log.w(TAG, "Unable to bind with intent: " + mIntent);
            return false;
        }

        private void addProxy(IBluetoothProfileServiceConnection proxy) {
            mProxies.register(proxy);
            if (mService != null) {
                try {
                    proxy.onServiceConnected(mClassName, mService);
                } catch (RemoteException e) {
                    Log.e(TAG, "Unable to connect to proxy", e);
                }
            } else {
                if (isBluetoothAvailableForBinding() == false) {
                    Log.w(TAG, "addProxy: Trying to bind to profile: " + mClassName +
                           ", while Bluetooth is disabled");
                    mProxies.unregister(proxy);
                    return;
                }

                if (!mHandler.hasMessages(MESSAGE_BIND_PROFILE_SERVICE, this)) {
                    Message msg = mHandler.obtainMessage(MESSAGE_BIND_PROFILE_SERVICE);
                    msg.obj = this;
                    msg.arg1 = DEFAULT_REBIND_COUNT;
                    mHandler.sendMessage(msg);
                }
            }
        }

        private void removeProxy(IBluetoothProfileServiceConnection proxy) {
            if (proxy != null) {
                if (mProxies.unregister(proxy)) {
                    try {
                        proxy.onServiceDisconnected(mClassName);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Unable to disconnect proxy", e);
                    }
                }

                Log.w(TAG, "removing the proxy, count is "
                               + mProxies.getRegisteredCallbackCount());
            } else {
                Log.w(TAG, "Trying to remove a null proxy");
            }
        }

        private void removeAllProxies() {
            onServiceDisconnected(mClassName);
            mProxies.kill();
        }

        private boolean isEmpty() {
            return (mProxies != null && mProxies.getRegisteredCallbackCount() == 0);
        }

        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            // remove timeout message
            mHandler.removeMessages(MESSAGE_BIND_PROFILE_SERVICE, this);
            mService = service;
            mClassName = className;
            try {
                mService.linkToDeath(this, 0);
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to linkToDeath", e);
            }

            synchronized (mProxies) {
                final int n = mProxies.beginBroadcast();
                try {
                    for (int i = 0; i < n; i++) {
                        try {
                            mProxies.getBroadcastItem(i).onServiceConnected(className, service);
                        } catch (RemoteException e) {
                            Log.e(TAG, "Unable to connect to proxy", e);
                        }
                    }
                } finally {
                    mProxies.finishBroadcast();
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            if (mService == null) return;
            try {
                mService.unlinkToDeath(this, 0);
            } catch (NoSuchElementException e) {
                Log.e(TAG, "Unable to unlinkToDeath", e);
            }
            mService = null;
            mClassName = null;

            synchronized (mProxies) {
                final int n = mProxies.beginBroadcast();
                try {
                    for (int i = 0; i < n; i++) {
                        try {
                            mProxies.getBroadcastItem(i).onServiceDisconnected(className);
                        } catch (RemoteException e) {
                            Log.e(TAG, "Unable to disconnect from proxy #" + i, e);
                        }
                    }
                } finally {
                    mProxies.finishBroadcast();
                }
            }
        }

        @Override
        public void binderDied() {
            if (DBG) {
                Log.w(TAG, "Profile service for profile: " + mClassName + " died.");
            }
            onServiceDisconnected(mClassName);

            if (isBluetoothAvailableForBinding() == false) {
                Log.w(TAG, "binderDied: Trying to bind to profile: " + mClassName +
                           ", while Bluetooth is disabled");
                return;
            }
            // Trigger rebind
            Message msg = mHandler.obtainMessage(MESSAGE_BIND_PROFILE_SERVICE);
            msg.obj = this;
            mHandler.sendMessageDelayed(msg, TIMEOUT_BIND_MS);
        }
    }

    private void sendBluetoothStateCallback(boolean isUp) {
        synchronized (mStateChangeCallbacks) {
            try {
                int n = mStateChangeCallbacks.beginBroadcast();
                if (DBG) {
                    Log.d(TAG, "Broadcasting onBluetoothStateChange(" + isUp + ") to " + n
                            + " receivers.");
                }
                for (int i = 0; i < n; i++) {
                    try {
                        mStateChangeCallbacks.getBroadcastItem(i).onBluetoothStateChange(isUp);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Unable to call onBluetoothStateChange() on callback #" + i, e);
                    }
                }
            } finally {
                mStateChangeCallbacks.finishBroadcast();
            }
        }
    }

    /**
     * Inform BluetoothAdapter instances that Adapter service is up
     */
    private void sendBluetoothServiceUpCallback() {
        synchronized (mCallbacks) {
            mBluetoothLock.readLock().lock();
            try {
                int n = mCallbacks.beginBroadcast();
                Log.d(TAG, "Broadcasting onBluetoothServiceUp() to " + n + " receivers.");
                for (int i = 0; i < n; i++) {
                    try {
                        mCallbacks.getBroadcastItem(i).onBluetoothServiceUp(mBluetooth);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Unable to call onBluetoothServiceUp() on callback #" + i, e);
                    }
                }
            } finally {
                mCallbacks.finishBroadcast();
                mBluetoothLock.readLock().unlock();
            }
        }
    }

    /**
     * Inform BluetoothAdapter instances that Adapter service is down
     */
    private void sendBluetoothServiceDownCallback() {
        synchronized (mCallbacks) {
            try {
                int n = mCallbacks.beginBroadcast();
                Log.d(TAG, "Broadcasting onBluetoothServiceDown() to " + n + " receivers.");
                for (int i = 0; i < n; i++) {
                    try {
                        mCallbacks.getBroadcastItem(i).onBluetoothServiceDown();
                    } catch (RemoteException e) {
                        Log.e(TAG, "Unable to call onBluetoothServiceDown() on callback #" + i, e);
                    }
                }
            } finally {
                mCallbacks.finishBroadcast();
            }
        }
    }

    public String getAddress(AttributionSource attributionSource) {
        if (!checkConnectPermissionForDataDelivery(mContext, attributionSource, "getAddress")) {
            return null;
        }

        if (!isCallerSystem(getCallingAppId()) && !checkIfCallerIsForegroundUser()) {
            Log.w(TAG, "getAddress(): not allowed for non-active and non system user");
            return null;
        }

        if (mContext.checkCallingOrSelfPermission(Manifest.permission.LOCAL_MAC_ADDRESS)
                != PackageManager.PERMISSION_GRANTED) {
            return BluetoothAdapter.DEFAULT_MAC_ADDRESS;
        }

        mBluetoothLock.readLock().lock();
        try {
            if (mBluetooth != null) {
                return synchronousGetAddress(attributionSource);
            }
        } catch (RemoteException | TimeoutException e) {
            Log.e(TAG,
                    "getAddress(): Unable to retrieve address remotely. Returning cached address",
                    e);
        } finally {
            mBluetoothLock.readLock().unlock();
        }

        // mAddress is accessed from outside.
        // It is alright without a lock. Here, bluetooth is off, no other thread is
        // changing mAddress
        return mAddress;
    }

    public String getName(AttributionSource attributionSource) {
        if (!checkConnectPermissionForDataDelivery(mContext, attributionSource, "getName")) {
            return null;
        }

        if (!isCallerSystem(getCallingAppId()) && !checkIfCallerIsForegroundUser()) {
            Log.w(TAG, "getName(): not allowed for non-active and non system user");
            return null;
        }

        mBluetoothLock.readLock().lock();
        try {
            if (mBluetooth != null) {
                return synchronousGetName(attributionSource);
            }
        } catch (RemoteException | TimeoutException e) {
            Log.e(TAG, "getName(): Unable to retrieve name remotely. Returning cached name", e);
        } finally {
            mBluetoothLock.readLock().unlock();
        }

        // mName is accessed from outside.
        // It alright without a lock. Here, bluetooth is off, no other thread is
        // changing mName
        return mName;
    }

    public boolean factoryReset() {
        final int callingUid = Binder.getCallingUid();
        final boolean callerSystem = UserHandle.getAppId(callingUid) == Process.SYSTEM_UID;

        if (!callerSystem) {
            if (!checkIfCallerIsForegroundUser()) {
                Log.w(TAG, "factoryReset(): not allowed for non-active and non system user");
                return false;
            }

            mContext.enforceCallingOrSelfPermission(
                   BLUETOOTH_PRIVILEGED, "Need BLUETOOTH PRIVILEGED permission");
        }
        persistBluetoothSetting(BLUETOOTH_ON_BLUETOOTH);

        /* Wait for stable state if bluetooth is temporary state. */
        int state = getState();
        if (state == BluetoothAdapter.STATE_BLE_TURNING_ON
                || state == BluetoothAdapter.STATE_TURNING_ON
                || state == BluetoothAdapter.STATE_TURNING_OFF) {
            if (!waitForState(Set.of(BluetoothAdapter.STATE_BLE_ON, BluetoothAdapter.STATE_ON))) {
                return false;
            }
        }

        // Clear registered LE apps to force shut-off
        clearBleApps();
        try {
            mBluetoothLock.writeLock().lock();
            if (mBluetooth == null) {
                mEnable = true;
                handleEnable(mQuietEnable);
            } else if (state == BluetoothAdapter.STATE_OFF) {
                mEnable = true;
                mBluetooth.factoryReset(mContext.getAttributionSource(),
                                        SynchronousResultReceiver.get());
                handleEnable(mQuietEnable);
            } else if (state == BluetoothAdapter.STATE_BLE_ON) {
                addActiveLog(
                BluetoothProtoEnums.ENABLE_DISABLE_REASON_FACTORY_RESET,
                mContext.getPackageName(), false);
                synchronousOnBrEdrDown(mContext.getAttributionSource());
                mBluetooth.factoryReset(mContext.getAttributionSource(),
                                        SynchronousResultReceiver.get());
            } else if (state == BluetoothAdapter.STATE_ON) {
                addActiveLog(
                BluetoothProtoEnums.ENABLE_DISABLE_REASON_FACTORY_RESET,
                mContext.getPackageName(), false);
                handleDisable();
                mBluetooth.factoryReset(mContext.getAttributionSource(),
                                        SynchronousResultReceiver.get());
            }
        } catch (RemoteException | TimeoutException e) {
            Log.e(TAG, "factoryReset(): Unable to do factoryReset.", e);
            return false;
        } finally {
            mBluetoothLock.writeLock().unlock();
        }
        return true;
    }

    private class BluetoothServiceConnection implements ServiceConnection {
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            String name = componentName.getClassName();
            if (DBG) {
                Log.d(TAG, "BluetoothServiceConnection: " + name);
            }
            Message msg = mHandler.obtainMessage(MESSAGE_BLUETOOTH_SERVICE_CONNECTED);
            if (name.equals("com.android.bluetooth.btservice.AdapterService")) {
                msg.arg1 = SERVICE_IBLUETOOTH;
                mHandler.removeMessages(MESSAGE_TIMEOUT_BIND);
            } else if (name.equals("com.android.bluetooth.gatt.GattService")) {
                msg.arg1 = SERVICE_IBLUETOOTHGATT;
            } else {
                Log.e(TAG, "Unknown service connected: " + name);
                return;
            }
            msg.obj = service;
            mHandler.sendMessage(msg);
        }

        public void onServiceDisconnected(ComponentName componentName) {
            // Called if we unexpectedly disconnect.
            String name = componentName.getClassName();
            if (DBG) {
                Log.d(TAG, "BluetoothServiceConnection, disconnected: " + name);
            }
            Message msg = mHandler.obtainMessage(MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED);
            if (name.equals("com.android.bluetooth.btservice.AdapterService")) {
                msg.arg1 = SERVICE_IBLUETOOTH;
            } else if (name.equals("com.android.bluetooth.gatt.GattService")) {
                msg.arg1 = SERVICE_IBLUETOOTHGATT;
            } else {
                Log.e(TAG, "Unknown service disconnected: " + name);
                return;
            }
            mHandler.sendMessage(msg);
        }
    }

    private BluetoothServiceConnection mConnection = new BluetoothServiceConnection();

    @VisibleForTesting
    class BluetoothHandler extends Handler {
        boolean mGetNameAddressOnly = false;
        private int mWaitForEnableRetry;
        private int mWaitForDisableRetry;

        BluetoothHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_GET_NAME_AND_ADDRESS:
                    if (DBG) {
                        Log.d(TAG, "MESSAGE_GET_NAME_AND_ADDRESS");
                    }
                    mBluetoothLock.writeLock().lock();
                    try {
                        if ((mBluetooth == null) && (!mBinding)) {
                            if (DBG) {
                                Log.d(TAG, "Binding to service to get name and address");
                            }
                            mGetNameAddressOnly = true;
                            Message timeoutMsg = mHandler.obtainMessage(MESSAGE_TIMEOUT_BIND);
                            mHandler.sendMessageDelayed(timeoutMsg, TIMEOUT_BIND_MS);
                            Intent i = new Intent(IBluetooth.class.getName());
                            if (!doBind(i, mConnection,
                                    Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT,
                                    UserHandle.CURRENT)) {
                                mHandler.removeMessages(MESSAGE_TIMEOUT_BIND);
                            } else {
                                mBinding = true;
                                mBindingUserID = ActivityManager.getCurrentUser();
                                Log.d(TAG, "Binding BT service. Current user: " + mBindingUserID);
                            }
                        } else if (mBluetooth != null) {
                            try {
                                storeNameAndAddress(
                                        synchronousGetName(mContext.getAttributionSource()),
                                        synchronousGetAddress(mContext.getAttributionSource()));
                            } catch (RemoteException | TimeoutException e) {
                                Log.e(TAG, "Unable to grab names", e);
                            }
                            if (mGetNameAddressOnly && !mEnable) {
                                unbindAndFinish();
                            }
                            mGetNameAddressOnly = false;
                        }
                    } finally {
                        mBluetoothLock.writeLock().unlock();
                    }
                    break;

                case MESSAGE_ENABLE:
                    int quietEnable = msg.arg1;
                    int isBle = msg.arg2;

                    Log.d(TAG, "MESSAGE_ENABLE: isBle: " + isBle + " msg.obj : " + msg.obj);
                    if (mShutdownInProgress) {
                        Log.d(TAG, "Skip Bluetooth Enable in device shutdown process");
                        break;
                    }

                    if (mHandler.hasMessages(MESSAGE_HANDLE_DISABLE_DELAYED)
                            || mHandler.hasMessages(MESSAGE_HANDLE_ENABLE_DELAYED)) {
                        if (msg.obj == null) {
                            int delay = ENABLE_DISABLE_DELAY_MS;

                            if (mHandler.hasMessages(MESSAGE_DISABLE)) {
                                delay = ENABLE_DISABLE_DELAY_MS * 2;
                            }
                            // Keep only one MESSAGE_ENABLE and ensure it is the last one
                            // to be taken out of the queue
                            mHandler.removeMessages(MESSAGE_ENABLE);
                            // We are handling enable or disable right now, wait for it.
                            mHandler.sendMessageDelayed(mHandler.obtainMessage(
                                MESSAGE_ENABLE, quietEnable, isBle, 1), delay);
                            Log.d(TAG, "Queue new MESSAGE_ENABLE");
                        } else {
                            mHandler.sendMessageDelayed(mHandler.obtainMessage(
                                MESSAGE_ENABLE, quietEnable, isBle, 1), ENABLE_DISABLE_DELAY_MS);
                            Log.d(TAG, "Re-Queue previous MESSAGE_ENABLE");
                            if (mHandler.hasMessages(MESSAGE_DISABLE)) {
                                // Ensure the original order of just entering the queue
                                // if MESSAGE_DISABLE present
                                mHandler.removeMessages(MESSAGE_DISABLE);
                                mHandler.sendMessageDelayed(mHandler.obtainMessage(
                                    MESSAGE_DISABLE, 0, 1), ENABLE_DISABLE_DELAY_MS * 2);
                                Log.d(TAG, "Re-Queue previous MESSAGE_DISABLE");
                            }
                        }
                        break;
                    } else if(msg.obj == null && mHandler.hasMessages(MESSAGE_DISABLE)) {
                        mHandler.removeMessages(MESSAGE_ENABLE);
                        mHandler.sendMessageDelayed(mHandler.obtainMessage(
                            MESSAGE_ENABLE, quietEnable, isBle, 1), ENABLE_DISABLE_DELAY_MS * 2);
                        Log.d(TAG, "MESSAGE_DISABLE exist. Queue new MESSAGE_ENABLE");
                        break;
                    }

                    if (DBG) {
                        Log.d(TAG, "MESSAGE_ENABLE(" + quietEnable + "): mBluetooth ="
                                + mBluetooth);
                    }
                    mHandler.removeMessages(MESSAGE_RESTART_BLUETOOTH_SERVICE);
                    mEnable = true;

                    if (isBle == 0) {
                        persistBluetoothSetting(BLUETOOTH_ON_BLUETOOTH);
                    }

                    mQuietEnable = (quietEnable == 1);
                    // Use service interface to get the exact state
                    mBluetoothLock.readLock().lock();
                    try {
                        if (mBluetooth != null) {
                            boolean isHandled = true;
                            int state = synchronousGetState();
                            switch (state) {
                                case BluetoothAdapter.STATE_BLE_ON:
                                    if (isBluetoothPersistedStateOnBluetooth() ||
                                        mEnableExternal) {
                                        Log.w(TAG, "BLE_ON State:Enable from Settings or" +
                                                    "BT on persisted, going to ON");
                                        mBluetooth.updateQuietModeStatus(mQuietEnable,
                                                mContext.getAttributionSource());
                                        synchronousOnLeServiceUp(mContext.getAttributionSource());
                                        persistBluetoothSetting(BLUETOOTH_ON_BLUETOOTH);

                                        // waive WRITE_SECURE_SETTINGS permission check
                                        long callingIdentity = Binder.clearCallingIdentity();
                                        Binder.restoreCallingIdentity(callingIdentity);
                                    } else if (isBle == 1) {
                                        Log.w(TAG, "BLE_ON State:Queued enable from ble app," +
                                                    " stay in ble on");
                                    }
                                    break;
                                case BluetoothAdapter.STATE_BLE_TURNING_ON:
                                case BluetoothAdapter.STATE_TURNING_ON:
                                case BluetoothAdapter.STATE_ON:
                                    Log.i(TAG, "MESSAGE_ENABLE: already enabled");
                                    break;
                                default:
                                    isHandled = false;
                                    break;
                            }
                            if (isHandled) break;
                        }
                    } catch (RemoteException | TimeoutException e) {
                        Log.e(TAG, "", e);
                    } finally {
                        mBluetoothLock.readLock().unlock();
                    }

                    if (mBluetooth == null) {
                        Log.d(TAG, "MESSAGE_ENABLE: handleEnable");
                        handleEnable(mQuietEnable);
                    } else {
                        //
                        // We need to wait until transitioned to STATE_OFF and
                        // the previous Bluetooth process has exited. The
                        // waiting period has three components:
                        // (a) Wait until the local state is STATE_OFF. This
                        //     is accomplished by sending delay a message
                        //     MESSAGE_HANDLE_ENABLE_DELAYED
                        // (b) Wait until the STATE_OFF state is updated to
                        //     all components.
                        // (c) Wait until the Bluetooth process exits, and
                        //     ActivityManager detects it.
                        // The waiting for (b) and (c) is accomplished by
                        // delaying the MESSAGE_RESTART_BLUETOOTH_SERVICE
                        // message. The delay time is backed off if Bluetooth
                        // continuously failed to turn on itself.
                        //
                        mWaitForEnableRetry = 0;
                        Log.d(TAG, "Re-Queue MESSAGE_HANDLE_ENABLE_DELAYED");
                        Message enableDelayedMsg =
                                mHandler.obtainMessage(MESSAGE_HANDLE_ENABLE_DELAYED);
                        mHandler.sendMessageDelayed(enableDelayedMsg, ENABLE_DISABLE_DELAY_MS);
                    }
                    break;

                case MESSAGE_DISABLE:
                    if (mHandler.hasMessages(MESSAGE_HANDLE_DISABLE_DELAYED) || mBinding
                            || mHandler.hasMessages(MESSAGE_HANDLE_ENABLE_DELAYED)) {
                        if (msg.arg2 == 0) {
                            int delay = ENABLE_DISABLE_DELAY_MS;

                            if (mHandler.hasMessages(MESSAGE_ENABLE)) {
                                delay = ENABLE_DISABLE_DELAY_MS * 2;
                            }
                            // Keep only one MESSAGE_DISABLE and ensure it is the last one
                            // to be taken out of the queue
                            mHandler.removeMessages(MESSAGE_DISABLE);
                            // We are handling enable or disable right now, wait for it.
                            mHandler.sendMessageDelayed(mHandler.obtainMessage(
                                MESSAGE_DISABLE, 0, 1), delay);
                            Log.d(TAG, "Queue new MESSAGE_DISABLE");
                        } else {
                            mHandler.sendMessageDelayed(mHandler.obtainMessage(
                                MESSAGE_DISABLE, 0, 1), ENABLE_DISABLE_DELAY_MS);
                            Log.d(TAG, "Re-Queue previous MESSAGE_DISABLE");
                            if (mHandler.hasMessages(MESSAGE_ENABLE)) {
                                // Ensure the original order of just entering the queue
                                // if MESSAGE_DISABLE present
                                mHandler.removeMessages(MESSAGE_ENABLE);
                                mHandler.sendMessageDelayed(mHandler.obtainMessage(
                                   MESSAGE_ENABLE, mQuietEnableExternal ? 1: 0,
                                   mEnableExternal ? 0:1, 1), ENABLE_DISABLE_DELAY_MS * 2);
                                Log.d(TAG, "Re-Queue previous MESSAGE_ENABLE");
                            }
                        }
                        break;
                    } else if(msg.arg2 == 0 && mHandler.hasMessages(MESSAGE_ENABLE)) {
                        mHandler.removeMessages(MESSAGE_DISABLE);
                        mHandler.sendMessageDelayed(mHandler.obtainMessage(
                            MESSAGE_DISABLE, 0, 1), ENABLE_DISABLE_DELAY_MS * 2);
                        Log.d(TAG, "MESSAGE_ENABLE exist. Queue new MESSAGE_DISABLE");
                        break;
                    }

                    if (DBG) {
                        Log.d(TAG, "MESSAGE_DISABLE: mBluetooth = " + mBluetooth
                                + ", mBinding = " + mBinding + " mEnable = " + mEnable);
                    }
                    mHandler.removeMessages(MESSAGE_RESTART_BLUETOOTH_SERVICE);

                    if (mEnable && mBluetooth != null) {
                        mWaitForDisableRetry = 0;
                        Message disableDelayedMsg =
                                mHandler.obtainMessage(MESSAGE_HANDLE_DISABLE_DELAYED, 0, 0);
                        mHandler.sendMessageDelayed(disableDelayedMsg, ENABLE_DISABLE_DELAY_MS);
                        Log.d(TAG, "Re-Queue MESSAGE_HANDLE_DISABLE_DELAYED(0)");
                    } else {
                        mEnable = false;
                        handleDisable();
                        Log.d(TAG, "MESSAGE_DISABLE: handleDisable");
                    }
                    break;

                case MESSAGE_HANDLE_ENABLE_DELAYED: {
                    /* The Bluetooth is turning off, wait for STATE_OFF then restart bluetooth
                     * if ble app running, then wait for BLE ON and continue bt turn on
                     */
                    Log.d(TAG, "MESSAGE_HANDLE_ENABLE_DELAYED, mState=" +
                        BluetoothAdapter.nameForState(mState) + " mEnableExternal = "
                        + mEnableExternal + " getServiceRestartMs()="
                        + getServiceRestartMs());
                    if ((mState == BluetoothAdapter.STATE_BLE_ON) && (isBleAppPresent() ||
                                mWaitForEnableRetry > 0)) {
                        Log.d(TAG, "isBleAppPresent(): " + isBleAppPresent() +
                                " mWaitForEnableRetry=" + mWaitForEnableRetry);
                        mWaitForEnableRetry = 0;
                        if (mEnableExternal || isBluetoothPersistedStateOnBluetooth()) {
                           try {
                                mBluetoothLock.readLock().lock();
                                if (mBluetooth != null) {
                                    mBluetooth.updateQuietModeStatus(mQuietEnable,
                                            mContext.getAttributionSource());
                                    synchronousOnLeServiceUp(mContext.getAttributionSource());
                                }
                           } catch (RemoteException | TimeoutException e) {
                                Log.e(TAG, "", e);
                           } finally {
                                mBluetoothLock.readLock().unlock();
                           }
                        } else {
                            Log.e(TAG, "BLE app running stay in BLE ON state");
                        }
                        break;
                    } else if (mState != BluetoothAdapter.STATE_OFF) {
                        if (mWaitForEnableRetry < MAX_WAIT_FOR_ENABLE_DISABLE_RETRIES) {
                            mWaitForEnableRetry++;
                            Message enableDelayedMsg =
                                    mHandler.obtainMessage(MESSAGE_HANDLE_ENABLE_DELAYED);
                            mHandler.sendMessageDelayed(enableDelayedMsg, ENABLE_DISABLE_DELAY_MS);
                            Log.d(TAG, "Re-Queue MESSAGE_HANDLE_ENABLE_DELAYED");
                            break;
                        } else {
                            Log.e(TAG, "Wait for STATE_OFF timeout");
                        }
                    }
                    // Either state is changed to STATE_OFF or reaches the maximum retry, we
                    // should move forward to the next step.
                    mWaitForEnableRetry = 0;
                    Message restartMsg =
                            mHandler.obtainMessage(MESSAGE_RESTART_BLUETOOTH_SERVICE);
                    mHandler.sendMessageDelayed(restartMsg, getServiceRestartMs());
                    Log.d(TAG, "Queue MESSAGE_RESTART_BLUETOOTH_SERVICE");
                    Log.d(TAG, "Handle enable is finished");
                    break;
                }

                case MESSAGE_HANDLE_DISABLE_DELAYED: {
                    boolean disabling = (msg.arg1 == 1);
                    Log.d(TAG, "MESSAGE_HANDLE_DISABLE_DELAYED: disabling:" + disabling);
                    if (!disabling) {
                        /* if bluetooth is in BLE ON state and enable is from ble app
                         * then skip disable, else wait for complete ON or timeout.
                         */
                        if ((mState == BluetoothAdapter.STATE_BLE_ON) &&
                            !mEnableExternal &&
                            !isBluetoothPersistedStateOnBluetooth() &&
                            isBleAppPresent()) {
                            Log.w(TAG, "Enable from BLE APP, stay in BLE ON");
                            mWaitForDisableRetry = 0;
                            mEnable = false;
                            break;
                        } else if (mState != BluetoothAdapter.STATE_ON) {
                            if (mWaitForDisableRetry < MAX_WAIT_FOR_ENABLE_DISABLE_RETRIES) {
                                mWaitForDisableRetry++;
                                Message disableDelayedMsg = mHandler.obtainMessage(
                                        MESSAGE_HANDLE_DISABLE_DELAYED, 0, 0);
                                mHandler.sendMessageDelayed(disableDelayedMsg,
                                        ENABLE_DISABLE_DELAY_MS);
                                Log.d(TAG, "Re-Queue MESSAGE_HANDLE_DISABLE_DELAYED(0)");
                                break;
                            } else {
                                Log.e(TAG, "Wait for STATE_ON timeout");
                            }
                        }
                        // Either state is changed to STATE_ON or reaches the maximum retry, we
                        // should move forward to the next step.
                        mWaitForDisableRetry = 0;
                        mEnable = false;
                        Log.d(TAG, "MESSAGE_HANDLE_DISABLE_DELAYED: handleDisable");
                        handleDisable();
                        // Wait for state exiting STATE_ON
                        Message disableDelayedMsg =
                                mHandler.obtainMessage(MESSAGE_HANDLE_DISABLE_DELAYED, 1, 0);
                        mHandler.sendMessageDelayed(disableDelayedMsg, ENABLE_DISABLE_DELAY_MS);
                        Log.d(TAG, "Re-Queue MESSAGE_HANDLE_DISABLE_DELAYED(1)");
                    } else {
                        // The Bluetooth is turning off, wait for exiting STATE_ON
                        if (mState == BluetoothAdapter.STATE_ON) {
                            if (mWaitForDisableRetry < MAX_WAIT_FOR_ENABLE_DISABLE_RETRIES) {
                                mWaitForDisableRetry++;
                                Message disableDelayedMsg = mHandler.obtainMessage(
                                        MESSAGE_HANDLE_DISABLE_DELAYED, 1, 0);
                                mHandler.sendMessageDelayed(disableDelayedMsg,
                                        ENABLE_DISABLE_DELAY_MS);
                                Log.d(TAG, "Re-Queue MESSAGE_HANDLE_DISABLE_DELAYED(1)");
                                break;
                            } else {
                                Log.e(TAG, "Wait for exiting STATE_ON timeout");
                            }
                        }
                        // Either state is exited from STATE_ON or reaches the maximum retry, we
                        // should move forward to the next step.
                        Log.d(TAG, "Handle disable is finished");
                    }
                    break;
                }

                case MESSAGE_RESTORE_USER_SETTING:
                    if (msg.arg1 == RESTORE_SETTING_TO_OFF) {
                        if (DBG) {
                            Log.d(TAG, "Restore Bluetooth state to disabled");
                        }
                        persistBluetoothSetting(BLUETOOTH_OFF);
                        mEnableExternal = false;
                        clearBleApps();
                        try {
                            mBluetoothLock.readLock().lock();
                            mEnableExternal = false;
                            if (mBluetooth != null) {
                                if (getState() == BluetoothAdapter.STATE_BLE_ON) {
                                    mEnable = false;
                                    synchronousOnBrEdrDown(mContext.getAttributionSource());
                                } else {
                                    sendDisableMsg(
                                    BluetoothProtoEnums.ENABLE_DISABLE_REASON_RESTORE_USER_SETTING,
                                    mContext.getPackageName());
                                }
                            }
                        } catch (RemoteException | TimeoutException e) {
                            Log.e(TAG, "Unable to initiate disable", e);
                        } finally {
                            mBluetoothLock.readLock().unlock();
                        }
                    } else if (msg.arg1 == RESTORE_SETTING_TO_ON) {
                        if (DBG) {
                            Log.d(TAG, "Restore Bluetooth state to enabled");
                        }
                        mQuietEnableExternal = false;
                        mEnableExternal = true;
                        // waive WRITE_SECURE_SETTINGS permission check
                        sendEnableMsg(false,
                                BluetoothProtoEnums.ENABLE_DISABLE_REASON_RESTORE_USER_SETTING,
                                mContext.getPackageName());
                    }
                    break;
                case MESSAGE_INFORM_ADAPTER_SERVICE_UP: {
                    if (DBG) Log.d(TAG,"MESSAGE_INFORM_ADAPTER_SERVICE_UP");
                    sendBluetoothServiceUpCallback();
                    break;
                }
                case MESSAGE_REGISTER_STATE_CHANGE_CALLBACK: {
                    IBluetoothStateChangeCallback callback =
                            (IBluetoothStateChangeCallback) msg.obj;
                    mStateChangeCallbacks.register(callback);
                    break;
                }
                case MESSAGE_UNREGISTER_STATE_CHANGE_CALLBACK: {
                    IBluetoothStateChangeCallback callback =
                            (IBluetoothStateChangeCallback) msg.obj;
                    mStateChangeCallbacks.unregister(callback);
                    break;
                }
                case MESSAGE_ADD_PROXY_DELAYED: {
                    ProfileServiceConnections psc = mProfileServices.get(msg.arg1);
                    if (psc == null) {
                        break;
                    }
                    IBluetoothProfileServiceConnection proxy =
                            (IBluetoothProfileServiceConnection) msg.obj;
                    psc.addProxy(proxy);
                    break;
                }
                case MESSAGE_BIND_PROFILE_SERVICE: {
                    Log.w(TAG, "MESSAGE_BIND_PROFILE_SERVICE");
                    ProfileServiceConnections psc = (ProfileServiceConnections) msg.obj;
                    removeMessages(MESSAGE_BIND_PROFILE_SERVICE, msg.obj);
                    if (psc == null) {
                        Log.w(TAG, "psc is null, breaking");
                        break;
                    }
                    if (msg.arg1 > 0) {
                        try {
                            mContext.unbindService(psc);
                            Log.w(TAG, "Calling psc.bindService from MESSAGE_BIND_PROFILE_SERVICE");
                        } catch (IllegalArgumentException e) {
                            Log.e(TAG, "Unable to unbind service with intent: " + psc.mIntent, e);
                        }
                        psc.bindService(msg.arg1 - 1);
                    }
                    break;
                }
                case MESSAGE_BLUETOOTH_SERVICE_CONNECTED: {
                    if (DBG) {
                        Log.d(TAG, "MESSAGE_BLUETOOTH_SERVICE_CONNECTED: " + msg.arg1);
                    }

                    IBinder service = (IBinder) msg.obj;
                    mBluetoothLock.writeLock().lock();
                    try {
                        if (msg.arg1 == SERVICE_IBLUETOOTHGATT) {
                            mBluetoothGatt = IBluetoothGatt.Stub.asInterface(service);
                            continueFromBleOnState();
                            break;
                        } // else must be SERVICE_IBLUETOOTH

                        mBinding = false;
                        mTryBindOnBindTimeout = false;
                        mBluetoothBinder = service;
                        mBluetooth = IBluetooth.Stub.asInterface(service);

                        int foregroundUserId = ActivityManager.getCurrentUser();
                        propagateForegroundUserId(foregroundUserId);

                        if (!isNameAndAddressSet()) {
                            Message getMsg = mHandler.obtainMessage(MESSAGE_GET_NAME_AND_ADDRESS);
                            mHandler.sendMessage(getMsg);
                            if (mGetNameAddressOnly) {
                                return;
                            }
                        }

                        //Register callback object
                        try {
                            synchronousRegisterCallback(mBluetoothCallback,
                                    mContext.getAttributionSource());
                        } catch (RemoteException | TimeoutException e) {
                            Log.e(TAG, "Unable to register BluetoothCallback", e);
                        }
                        //Inform BluetoothAdapter instances that service is up
                        Message informMsg =
                                    mHandler.obtainMessage(MESSAGE_INFORM_ADAPTER_SERVICE_UP);
                        mHandler.sendMessage(informMsg);

                        // Get the supported profiles list
                        try {
                            mSupportedProfileList = synchronousGetSupportedProfiles(
                                    mContext.getAttributionSource());
                        } catch (RemoteException | TimeoutException e) {
                            Log.e(TAG, "Unable to get the supported profiles list", e);
                        }

                        //Do enable request
                        try {
                            if (!synchronousEnable(mQuietEnable, mContext.getAttributionSource())) {
                                Log.e(TAG, "IBluetooth.enable() returned false");
                            }
                        } catch (RemoteException | TimeoutException e) {
                            Log.e(TAG, "Unable to call enable()", e);
                        }
                    } finally {
                        mBluetoothLock.writeLock().unlock();
                    }

                    if (!mEnable) {
                        /* Wait for BLE ON or ON state ,if enable is from BLE app
                         * skip disable, else wait for on state and handle disable
                         */
                        waitForState(Set.of(BluetoothAdapter.STATE_BLE_ON,
                                            BluetoothAdapter.STATE_ON));

                        int st = getState();
                        if ((st == BluetoothAdapter.STATE_TURNING_ON) ||
                           ((st == BluetoothAdapter.STATE_BLE_ON) &&
                           (mEnableExternal || isBluetoothPersistedStateOnBluetooth()))) {
                            waitForState(Set.of(BluetoothAdapter.STATE_ON));
                        } else if ((st == BluetoothAdapter.STATE_BLE_ON) && isBleAppPresent()) {
                            Log.e(TAG, "MESSAGE_BLUETOOTH_SERVICE_CONNECTED: ble app present");
                            break;
                        }
                        handleDisable();
                        waitForState(Set.of(BluetoothAdapter.STATE_OFF,
                                BluetoothAdapter.STATE_TURNING_ON,
                                BluetoothAdapter.STATE_TURNING_OFF,
                                BluetoothAdapter.STATE_BLE_TURNING_ON,
                                BluetoothAdapter.STATE_BLE_ON,
                                BluetoothAdapter.STATE_BLE_TURNING_OFF));
                    }
                    break;
                }
                case MESSAGE_BLUETOOTH_STATE_CHANGE: {
                    int prevState = msg.arg1;
                    int newState = msg.arg2;
                    if (DBG) {
                        Log.d(TAG,
                                "MESSAGE_BLUETOOTH_STATE_CHANGE: " + BluetoothAdapter.nameForState(
                                        prevState) + " > " + BluetoothAdapter.nameForState(
                                        newState));
                    }
                    mState = newState;
                    bluetoothStateChangeHandler(prevState, newState);
                    // handle error state transition case from TURNING_ON to OFF
                    // unbind and rebind bluetooth service and enable bluetooth
                    if ((prevState == BluetoothAdapter.STATE_BLE_TURNING_ON) && (newState
                            == BluetoothAdapter.STATE_OFF) && (mBluetooth != null) && mEnable) {
                        recoverBluetoothServiceFromError(false);
                    }
                    if ((prevState == BluetoothAdapter.STATE_TURNING_ON) &&
                            (newState == BluetoothAdapter.STATE_OFF) &&
                            (mBluetooth != null) && mEnable) {
                         persistBluetoothSetting(BLUETOOTH_OFF);
                    }
                    if ((prevState == BluetoothAdapter.STATE_TURNING_ON) &&
                           (newState == BluetoothAdapter.STATE_BLE_ON) &&
                           (mBluetooth != null) && mEnable) {
                        recoverBluetoothServiceFromError(true);
                    }
                    // If we tried to enable BT while BT was in the process of shutting down,
                    // wait for the BT process to fully tear down and then force a restart
                    // here.  This is a bit of a hack (b/29363429).
                    if ((prevState == BluetoothAdapter.STATE_BLE_TURNING_OFF) && (newState
                            == BluetoothAdapter.STATE_OFF)) {
                        if (mEnable) {
                            Log.d(TAG, "Entering STATE_OFF but mEnabled is true; restarting.");
                            mHandler.removeMessages(MESSAGE_RESTART_BLUETOOTH_SERVICE);
                            waitForState(Set.of(BluetoothAdapter.STATE_OFF));
                            Message restartMsg =
                                    mHandler.obtainMessage(MESSAGE_RESTART_BLUETOOTH_SERVICE);
                            mHandler.sendMessageDelayed(restartMsg, getServiceRestartMs());
                        }
                    }
                    if (newState == BluetoothAdapter.STATE_ON
                            || newState == BluetoothAdapter.STATE_BLE_ON) {
                        // bluetooth is working, reset the counter
                        if (mErrorRecoveryRetryCounter != 0) {
                            Log.w(TAG, "bluetooth is recovered from error");
                            mErrorRecoveryRetryCounter = 0;
                        }
                    }
                    break;
                }
                case MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED: {
                    Log.e(TAG, "MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED(" + msg.arg1 + ")");
                    mBluetoothLock.writeLock().lock();
                    try {
                        if (msg.arg1 == SERVICE_IBLUETOOTH) {
                            // if service is unbinded already, do nothing and return
                            if (mBluetooth == null) {
                                break;
                            }
                            mBluetooth = null;
                            mSupportedProfileList.clear();
                        } else if (msg.arg1 == SERVICE_IBLUETOOTHGATT) {
                            mBluetoothGatt = null;
                            break;
                        } else {
                            Log.e(TAG, "Unknown argument for service disconnect!");
                            break;
                        }
                    } finally {
                        mBluetoothLock.writeLock().unlock();
                    }

                    // Make sure BT process exit completely
                    int[] pids = null;
                    try {
                        pids = (int[]) Class.forName("android.os.Process")
                            .getMethod("getPidsForCommands", String[].class)
                            .invoke(new String[]{ "com.android.bluetooth" });
                    } catch (Exception e) {
                        Log.e(TAG, "Error to call getPidsForCommands");
                    }
                    if (pids != null && pids.length > 0) {
                        for(int pid : pids) {
                            Log.e(TAG, "Killing BT process with PID = " + pid);
                            Process.killProcess(pid);
                        }
                    }

                    // log the unexpected crash
                    addCrashLog();
                    addActiveLog(BluetoothProtoEnums.ENABLE_DISABLE_REASON_CRASH,
                            mContext.getPackageName(), false);
                    if (mEnable) {
                        mEnable = false;
                        // Send a Bluetooth Restart message
                        Message restartMsg =
                                mHandler.obtainMessage(MESSAGE_RESTART_BLUETOOTH_SERVICE);
                        mHandler.sendMessageDelayed(restartMsg, getServiceRestartMs());
                    }

                    sendBluetoothServiceDownCallback();

                    // Send BT state broadcast to update
                    // the BT icon correctly
                    if ((mState == BluetoothAdapter.STATE_TURNING_ON) || (mState
                            == BluetoothAdapter.STATE_ON)) {
                        bluetoothStateChangeHandler(BluetoothAdapter.STATE_ON,
                                BluetoothAdapter.STATE_TURNING_OFF);
                        mState = BluetoothAdapter.STATE_TURNING_OFF;
                    }
                    if (mState == BluetoothAdapter.STATE_TURNING_OFF) {
                        bluetoothStateChangeHandler(BluetoothAdapter.STATE_TURNING_OFF,
                                BluetoothAdapter.STATE_OFF);
                    }

                    mHandler.removeMessages(MESSAGE_BLUETOOTH_STATE_CHANGE);
                    mState = BluetoothAdapter.STATE_OFF;
                    break;
                }
                case MESSAGE_RESTART_BLUETOOTH_SERVICE: {
                    mErrorRecoveryRetryCounter++;
                    Log.d(TAG, "MESSAGE_RESTART_BLUETOOTH_SERVICE: retry count="
                            + mErrorRecoveryRetryCounter);
                    if (mErrorRecoveryRetryCounter < MAX_ERROR_RESTART_RETRIES) {
                        /* Enable without persisting the setting as
                         it doesnt change when IBluetooth
                         service restarts */
                        mEnable = true;
                        addActiveLog(BluetoothProtoEnums.ENABLE_DISABLE_REASON_RESTARTED,
                                mContext.getPackageName(), true);
                        handleEnable(mQuietEnable);
                    } else {
                        mBluetoothLock.writeLock().lock();
                        mBluetooth = null;
                        mBluetoothLock.writeLock().unlock();
                        Log.e(TAG, "Reach maximum retry to restart Bluetooth!");
                    }
                    break;
                }
                case MESSAGE_TIMEOUT_BIND: {
                    Log.e(TAG, "MESSAGE_TIMEOUT_BIND");
                    mBluetoothLock.writeLock().lock();
                    mBinding = false;
                    mBluetoothLock.writeLock().unlock();
                    // Ensure try BIND for one more time
                    if(!mTryBindOnBindTimeout) {
                        int userID = ActivityManager.getCurrentUser();

                        Log.d(TAG, "Current user: " + userID);
                        if (mBindingUserID == userID) {
                            Log.e(TAG, " Trying to Bind again");
                            mTryBindOnBindTimeout = true;
                            handleEnable(mQuietEnable);
                        }
                    } else {
                        Log.e(TAG, "Bind trails excedded");
                        mTryBindOnBindTimeout = false;
                    }
                    break;
                }

                 case MESSAGE_TIMEOUT_UNBIND: {
                     Log.d(TAG, "MESSAGE_TIMEOUT_UNBIND : mBluetooth=" + mBluetooth + " mBinding="
                    + mBinding + " mState=" + BluetoothAdapter.nameForState(mState));
                    if (mState == BluetoothAdapter.STATE_OFF) {
                        unbindAndFinish();
                    }
                    break;
                }

                case MESSAGE_USER_SWITCHED: {
                    if (DBG) {
                        Log.d(TAG, "MESSAGE_USER_SWITCHED");
                    }
                    mHandler.removeMessages(MESSAGE_USER_SWITCHED);
                    mBluetoothNotificationManager.createNotificationChannels();

                    try {
                        mBluetoothLock.writeLock().lock();
                        int state = getState();

                        if (mBluetooth != null && ((state == BluetoothAdapter.STATE_ON) ||
                                (state == BluetoothAdapter.STATE_BLE_ON && isBleAppPresent()))) {
                            /* disable and enable BT when detect a user switch */
                            if (state == BluetoothAdapter.STATE_ON) {
                                restartForReason(
                                        BluetoothProtoEnums.ENABLE_DISABLE_REASON_USER_SWITCH);
                            } else {
                                if (DBG) {
                                    Log.d(TAG, "Turn off from BLE state");
                                }
                                clearBleApps();
                                addActiveLog(BluetoothProtoEnums.ENABLE_DISABLE_REASON_USER_SWITCH,
                                          mContext.getPackageName(), false);
                                mEnable = false;
                                synchronousOnBrEdrDown(mContext.getAttributionSource());
                            }
                        } else if (mBinding || mBluetooth != null) {
                            Message userMsg = mHandler.obtainMessage(MESSAGE_USER_SWITCHED);
                            userMsg.arg2 = 1 + msg.arg2;
                            // if user is switched when service is binding retry after a delay
                            mHandler.sendMessageDelayed(userMsg, USER_SWITCHED_TIME_MS);
                            if (DBG) {
                                Log.d(TAG, "Retry MESSAGE_USER_SWITCHED " + userMsg.arg2);
                            }
                        }
                    } catch (RemoteException | TimeoutException e) {
                        Log.e(TAG, "MESSAGE_USER_SWITCHED: Remote exception", e);
                    } finally {
                        mBluetoothLock.writeLock().unlock();
                    }
                    break;
                }
                case MESSAGE_USER_UNLOCKED: {
                    if (DBG) {
                        Log.d(TAG, "MESSAGE_USER_UNLOCKED");
                    }
                    mHandler.removeMessages(MESSAGE_USER_SWITCHED);

                    if (mEnable && !mBinding && (mBluetooth == null)) {
                        // We should be connected, but we gave up for some
                        // reason; maybe the Bluetooth service wasn't encryption
                        // aware, so try binding again.
                        if (DBG) {
                            Log.d(TAG, "Enabled but not bound; retrying after unlock");
                        }
                        handleEnable(mQuietEnable);
                    }
                    break;
                }
                case MESSAGE_INIT_FLAGS_CHANGED: {
                    if (DBG) {
                        Log.d(TAG, "MESSAGE_INIT_FLAGS_CHANGED");
                    }
                    mHandler.removeMessages(MESSAGE_INIT_FLAGS_CHANGED);
                    if (mBluetoothModeChangeHelper.isMediaProfileConnected()) {
                        Log.i(TAG, "Delaying MESSAGE_INIT_FLAGS_CHANGED by "
                                + DELAY_FOR_RETRY_INIT_FLAG_CHECK_MS
                                + " ms due to existing connections");
                        mHandler.sendEmptyMessageDelayed(
                                MESSAGE_INIT_FLAGS_CHANGED,
                                DELAY_FOR_RETRY_INIT_FLAG_CHECK_MS);
                        break;
                    }
                    if (!isDeviceProvisioned()) {
                        Log.i(TAG, "Delaying MESSAGE_INIT_FLAGS_CHANGED by "
                                + DELAY_FOR_RETRY_INIT_FLAG_CHECK_MS
                                +  "ms because device is not provisioned");
                        mHandler.sendEmptyMessageDelayed(
                                MESSAGE_INIT_FLAGS_CHANGED,
                                DELAY_FOR_RETRY_INIT_FLAG_CHECK_MS);
                        break;
                    }
                    if (mBluetooth != null && isEnabled()) {
                        Log.i(TAG, "Restarting Bluetooth due to init flag change");
                        restartForReason(
                                BluetoothProtoEnums.ENABLE_DISABLE_REASON_INIT_FLAGS_CHANGED);
                    }
                    break;
                }
            }
        }

        @RequiresPermission(allOf = {
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_PRIVILEGED
        })
        private void restartForReason(int reason) {
            mBluetoothLock.readLock().lock();
            try {
                if (mBluetooth != null) {
                    synchronousUnregisterCallback(mBluetoothCallback,
                            mContext.getAttributionSource());
                }
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, "Unable to unregister", e);
            } finally {
                mBluetoothLock.readLock().unlock();
            }

            if (mState == BluetoothAdapter.STATE_TURNING_OFF) {
                // MESSAGE_USER_SWITCHED happened right after MESSAGE_ENABLE
                bluetoothStateChangeHandler(mState, BluetoothAdapter.STATE_OFF);
                mState = BluetoothAdapter.STATE_OFF;
            }
            if (mState == BluetoothAdapter.STATE_OFF) {
                bluetoothStateChangeHandler(mState, BluetoothAdapter.STATE_TURNING_ON);
                mState = BluetoothAdapter.STATE_TURNING_ON;
            }

            /* wait for stable state BLE_ON or ON */
            waitForState(Set.of(BluetoothAdapter.STATE_BLE_ON,
                                BluetoothAdapter.STATE_ON));

            if (mState == BluetoothAdapter.STATE_TURNING_ON) {
                bluetoothStateChangeHandler(mState, BluetoothAdapter.STATE_ON);
            }

            unbindAllBluetoothProfileServices();
            // disable
            addActiveLog(reason, mContext.getPackageName(), false);

            clearBleApps();

            handleDisable();
            // Pbap service need receive STATE_TURNING_OFF intent to close
            bluetoothStateChangeHandler(BluetoothAdapter.STATE_ON,
                    BluetoothAdapter.STATE_TURNING_OFF);

            /* wait for BLE_ON or OFF state. If its BLE ON state
             * post BLE ON state to bluetoothStateChangeHandler
             * to continue off and wait for off state
             */
            boolean didDisableTimeout =
            !waitForState(Set.of(BluetoothAdapter.STATE_BLE_ON,
                                 BluetoothAdapter.STATE_OFF));

            if(!didDisableTimeout) {
               int state = getState();

               if (state == BluetoothAdapter.STATE_BLE_ON) {
                   bluetoothStateChangeHandler(BluetoothAdapter.STATE_TURNING_OFF,
                                            BluetoothAdapter.STATE_BLE_ON);
               }

               didDisableTimeout =
                    !waitForState(Set.of(BluetoothAdapter.STATE_OFF));
            }
            bluetoothStateChangeHandler(BluetoothAdapter.STATE_TURNING_OFF,
                    BluetoothAdapter.STATE_OFF);
            sendBluetoothServiceDownCallback();

            if(!didDisableTimeout) {
                mBluetoothLock.writeLock().lock();
                try {
                    if (mBluetooth != null) {
                        mBluetooth = null;
                        // Unbind
                        mContext.unbindService(mConnection);
                    }
                    mBluetoothGatt = null;
                } finally {
                    mBluetoothLock.writeLock().unlock();
                }
            }

            //
            // If disabling Bluetooth times out, wait for an
            // additional amount of time to ensure the process is
            // shut down completely before attempting to restart.
            //
            if (didDisableTimeout) {
                SystemClock.sleep(3000);
                mHandler.removeMessages(MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED);
            } else {
                SystemClock.sleep(100);
            }

            mHandler.removeMessages(MESSAGE_BLUETOOTH_STATE_CHANGE);
            mState = BluetoothAdapter.STATE_OFF;
            // enable
            addActiveLog(reason, mContext.getPackageName(), true);
            // mEnable flag could have been reset on disableBLE. Reenable it.
            mEnable = true;
            handleEnable(mQuietEnable);
        }
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    private void handleEnable(boolean quietMode) {
        mQuietEnable = quietMode;

        mBluetoothLock.writeLock().lock();
        try {
            if ((mBluetooth == null) && (!mBinding)) {
                Log.d(TAG, "binding Bluetooth service");
                //Start bind timeout and bind
                Message timeoutMsg = mHandler.obtainMessage(MESSAGE_TIMEOUT_BIND);
                mHandler.sendMessageDelayed(timeoutMsg, TIMEOUT_BIND_MS);
                Intent i = new Intent(IBluetooth.class.getName());
                if (!doBind(i, mConnection, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT,
                        UserHandle.CURRENT)) {
                    mHandler.removeMessages(MESSAGE_TIMEOUT_BIND);
                } else {
                    mBinding = true;
                    mBindingUserID = ActivityManager.getCurrentUser();
                    Log.d(TAG, "Binding BT service. Current user: " + mBindingUserID);
                }
            } else if (mBluetooth != null) {
                //Enable bluetooth
                try {
                    if (!synchronousEnable(mQuietEnable, mContext.getAttributionSource())) {
                        Log.e(TAG, "IBluetooth.enable() returned false");
                    }
                } catch (RemoteException | TimeoutException e) {
                    Log.e(TAG, "Unable to call enable()", e);
                }
            }
        } finally {
            mBluetoothLock.writeLock().unlock();
        }
    }

    boolean doBind(Intent intent, ServiceConnection conn, int flags, UserHandle user) {
        ComponentName comp = resolveSystemService(intent, mContext.getPackageManager(), 0);
        intent.setComponent(comp);
        if (comp == null || !mContext.bindServiceAsUser(intent, conn, flags, user)) {
            Log.e(TAG, "Fail to bind to: " + intent);
            return false;
        }
        return true;
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    private void handleDisable() {
        mBluetoothLock.readLock().lock();
        try {
            if (mBluetooth != null) {
                if (DBG) {
                    Log.d(TAG, "Sending off request.");
                }
                if (!synchronousDisable(mContext.getAttributionSource())) {
                    Log.e(TAG, "IBluetooth.disable() returned false");
                }
            }
        } catch (RemoteException | TimeoutException e) {
            Log.e(TAG, "Unable to call disable()", e);
        } finally {
            mBluetoothLock.readLock().unlock();
        }
    }

    private static int getCallingAppId() {
        return UserHandle.getAppId(Binder.getCallingUid());
    }
    private static boolean isCallerSystem(int callingAppId) {
        return callingAppId == Process.SYSTEM_UID;
    }
    private static boolean isCallerShell(int callingAppId) {
        return callingAppId == Process.SHELL_UID;
    }
    private static boolean isCallerRoot(int callingAppId) {
        return callingAppId == Process.ROOT_UID;
    }

    private boolean checkIfCallerIsForegroundUser() {
        int callingUid = Binder.getCallingUid();
        UserHandle callingUser = UserHandle.getUserHandleForUid(callingUid);
        final long callingIdentity = Binder.clearCallingIdentity();
        UserManager userManager = mContext.getSystemService(UserManager.class);
        UserHandle uh = userManager.getProfileParent(callingUser);
        UserHandle parentUser = (uh != null) ? uh : USER_HANDLE_NULL;
        int callingAppId = UserHandle.getAppId(callingUid);
        boolean valid = false;
        try {
            UserHandle foregroundUser = UserHandle.of(ActivityManager.getCurrentUser());
            valid = (callingUser == foregroundUser) || parentUser == foregroundUser
                    || callingAppId == Process.NFC_UID || callingAppId == mSystemUiUid
                    || callingAppId == Process.SHELL_UID;
            if (DBG && !valid) {
                Log.d(TAG, "checkIfCallerIsForegroundUser: valid=" + valid + " callingUser="
                        + callingUser + " parentUser=" + parentUser + " foregroundUser="
                        + foregroundUser);
            }
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
        return valid;
    }

    private void sendBleStateChanged(int prevState, int newState) {
        if (DBG) {
            Log.d(TAG,
                    "Sending BLE State Change: " + BluetoothAdapter.nameForState(prevState) + " > "
                            + BluetoothAdapter.nameForState(newState));
        }
        // Send broadcast message to everyone else
        Intent intent = new Intent(BluetoothAdapter.ACTION_BLE_STATE_CHANGED);
        intent.putExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, prevState);
        intent.putExtra(BluetoothAdapter.EXTRA_STATE, newState);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL, null, getTempAllowlistBroadcastOptions());
    }

    private boolean isBleState(int state) {
        switch (state) {
            case BluetoothAdapter.STATE_BLE_ON:
            case BluetoothAdapter.STATE_BLE_TURNING_ON:
            case BluetoothAdapter.STATE_BLE_TURNING_OFF:
                return true;
        }
        return false;
    }

    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
    })
    private void bluetoothStateChangeHandler(int prevState, int newState) {
        boolean isStandardBroadcast = true;
        if (prevState == newState) { // No change. Nothing to do.
            return;
        }
        // Notify all proxy objects first of adapter state change
        if (newState == BluetoothAdapter.STATE_BLE_ON || newState == BluetoothAdapter.STATE_OFF) {
            boolean intermediate_off = (prevState == BluetoothAdapter.STATE_TURNING_OFF
                    && newState == BluetoothAdapter.STATE_BLE_ON);

            if (newState == BluetoothAdapter.STATE_OFF) {
                // If Bluetooth is off, send service down event to proxy objects, and unbind
                if (DBG) {
                    Log.d(TAG, "Bluetooth is complete off, send Service Down");
                }
                sendBluetoothServiceDownCallback();
                sendBluetoothStateCallback(false);
                unbindAndFinish();
                sendBleStateChanged(prevState, newState);

                /* Currently, the OFF intent is broadcasted externally only when we transition
                 * from TURNING_OFF to BLE_ON state. So if the previous state is a BLE state,
                 * we are guaranteed that the OFF intent has been broadcasted earlier and we
                 * can safely skip it.
                 * Conversely, if the previous state is not a BLE state, it indicates that some
                 * sort of crash has occurred, moving us directly to STATE_OFF without ever
                 * passing through BLE_ON. We should broadcast the OFF intent in this case. */
                isStandardBroadcast = !isBleState(prevState);

            } else if (!intermediate_off) {
                // connect to GattService
                if (DBG) {
                    Log.d(TAG, "Bluetooth is in LE only mode");
                }
                if (mBluetoothGatt != null || !mContext.getPackageManager()
                            .hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
                    continueFromBleOnState();
                } else {
                    if (DBG) {
                        Log.d(TAG, "Binding Bluetooth GATT service");
                    }
                    Intent i = new Intent(IBluetoothGatt.class.getName());
                    doBind(i, mConnection, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT,
                            UserHandle.CURRENT);
                }
                sendBleStateChanged(prevState, newState);
                //Don't broadcase this as std intent
                isStandardBroadcast = false;

            } else if (intermediate_off) {
                if (DBG) {
                    Log.d(TAG, "Intermediate off, back to LE only mode");
                }
                // For LE only mode, broadcast as is
                sendBleStateChanged(prevState, newState);
                sendBluetoothStateCallback(false); // BT is OFF for general users
                // Broadcast as STATE_OFF
                newState = BluetoothAdapter.STATE_OFF;
                sendBrEdrDownCallback(mContext.getAttributionSource());
            }
        } else if (newState == BluetoothAdapter.STATE_ON) {
            boolean isUp = (newState == BluetoothAdapter.STATE_ON);
            sendBluetoothStateCallback(isUp);
            sendBleStateChanged(prevState, newState);

        } else if (newState == BluetoothAdapter.STATE_BLE_TURNING_ON) {
            sendBleStateChanged(prevState, newState);
            isStandardBroadcast = false;
        } else if (newState == BluetoothAdapter.STATE_BLE_TURNING_OFF) {
            sendBleStateChanged(prevState, newState);
            if (prevState != BluetoothAdapter.STATE_TURNING_OFF) {
                isStandardBroadcast = false;
            } else {
                // Broadcast as STATE_OFF for app that do not receive BLE update
                newState = BluetoothAdapter.STATE_OFF;
                sendBrEdrDownCallback(mContext.getAttributionSource());
            }
        } else if (newState == BluetoothAdapter.STATE_TURNING_ON
                || newState == BluetoothAdapter.STATE_TURNING_OFF) {
            sendBleStateChanged(prevState, newState);
        }

        if (isStandardBroadcast) {
            if (prevState == BluetoothAdapter.STATE_BLE_ON) {
                // Show prevState of BLE_ON as OFF to standard users
                prevState = BluetoothAdapter.STATE_OFF;
            }
            if (DBG) {
                Log.d(TAG,
                        "Sending State Change: " + BluetoothAdapter.nameForState(prevState) + " > "
                                + BluetoothAdapter.nameForState(newState));
            }
            Intent intent = new Intent(BluetoothAdapter.ACTION_STATE_CHANGED);
            intent.putExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, prevState);
            intent.putExtra(BluetoothAdapter.EXTRA_STATE, newState);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL, null,
                    getTempAllowlistBroadcastOptions());
        }
    }

    boolean waitForManagerState(int state) {
        return waitForState(Set.of(state), false);
    }

    private boolean waitForState(Set<Integer> states) {
        return waitForState(states, true);
    }
    private boolean waitForState(Set<Integer> states, boolean failIfUnbind) {
        for (int i = 0; i < 16; i++) {
            mBluetoothLock.readLock().lock();
            try {
                if (mBluetooth == null && failIfUnbind) {
                    Log.e(TAG, "waitForState " + states + " Bluetooth is not unbind");
                    return false;
                }
                if (mBluetooth == null && states.contains(BluetoothAdapter.STATE_OFF)) {
                    return true; // We are so OFF that the bluetooth is not bind
                }
                if (mBluetooth != null && states.contains(synchronousGetState())) {
                    return true;
                }
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, "getState()", e);
                break;
            } finally {
                mBluetoothLock.readLock().unlock();
            }
            SystemClock.sleep(300);
        }
        Log.e(TAG, "waitForState " + states + " time out");
        return false;
    }

    /* TODO(b/151672214) - Update for change from waitForOnOff -> waitForState
    private boolean waitForMonitoredState(Set<Integer> states) {
        int i = 0;
        while (i < 10) {
            synchronized(mConnection) {
                try {
                    if (mBluetooth == null) break;
                    if (on) {
                        if (mBluetooth.getState() == BluetoothAdapter.STATE_ON) return true;
                        if (mBluetooth.getState() == BluetoothAdapter.STATE_BLE_ON) {
                            bluetoothStateChangeHandler(BluetoothAdapter.STATE_BLE_TURNING_ON,
                                                        BluetoothAdapter.STATE_BLE_ON);
                            if (mBluetoothGatt != null) {
                                Log.d(TAG,"GattService is connected, execute waitForState");
                                boolean ret = waitForState(states);
                                return ret;
                            } else {
                                Log.d(TAG,
                                    "GattService connect in progress, return to avoid timeout");
                                return true;
                            }
                        }
                    } else if (off) {
                        if (mBluetooth.getState() == BluetoothAdapter.STATE_OFF) return true;
                        if (mBluetooth.getState() == BluetoothAdapter.STATE_BLE_ON) {
                            bluetoothStateChangeHandler(BluetoothAdapter.STATE_TURNING_OFF,
                                                        BluetoothAdapter.STATE_BLE_ON);
                            boolean ret = waitForState(states);
                            return ret;
                        }
                    } else {
                        if (mBluetooth.getState() != BluetoothAdapter.STATE_ON) return true;
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "getState()", e);
                    break;
                }
            }
            SystemClock.sleep(300);
            i++;
        }
        Log.e(TAG,"waitForMonitoredOnOff time out");
        return false;
    }
    */

    private void sendDisableMsg(int reason, String packageName) {
        BluetoothServerProxy.getInstance().handlerSendWhatMessage(mHandler, MESSAGE_DISABLE);
        addActiveLog(reason, packageName, false);
    }

    private void sendEnableMsg(boolean quietMode, int reason, String packageName) {
        sendEnableMsg(quietMode, reason, packageName, false);
    }

    private void sendEnableMsg(boolean quietMode, int reason, String packageName, boolean isBle) {
        mHandler.sendMessage(mHandler.obtainMessage(MESSAGE_ENABLE, quietMode ? 1 : 0,
                  isBle ? 1 : 0));
        addActiveLog(reason, packageName, true);
        mLastEnabledTime = SystemClock.elapsedRealtime();
    }

    private void addActiveLog(int reason, String packageName, boolean enable) {
        synchronized (mActiveLogs) {
            if (mActiveLogs.size() > ACTIVE_LOG_MAX_SIZE) {
                mActiveLogs.remove();
            }
            mActiveLogs.add(
                    new ActiveLog(reason, packageName, enable, System.currentTimeMillis()));

            int state =
                    enable
                            ? BluetoothStatsLog.BLUETOOTH_ENABLED_STATE_CHANGED__STATE__ENABLED
                            : BluetoothStatsLog.BLUETOOTH_ENABLED_STATE_CHANGED__STATE__DISABLED;

            BluetoothStatsLog.write_non_chained(
                    BluetoothStatsLog.BLUETOOTH_ENABLED_STATE_CHANGED,
                    Binder.getCallingUid(),
                    null,
                    state,
                    reason,
                    packageName);
        }
    }

    private void addCrashLog() {
        synchronized (mCrashTimestamps) {
            if (mCrashTimestamps.size() == CRASH_LOG_MAX_SIZE) {
                mCrashTimestamps.removeFirst();
            }
            mCrashTimestamps.add(System.currentTimeMillis());
            mCrashes++;
        }
    }

    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
    })
    private void recoverBluetoothServiceFromError(boolean clearBle) {
        Log.e(TAG, "recoverBluetoothServiceFromError");
        boolean repeatAirplaneRunnable = false;

        // 0 means we are matching unset `what` since we are using a token instead
        if (mHandler.hasMessages(0, ON_AIRPLANE_MODE_CHANGED_TOKEN)) {
            mHandler.removeCallbacksAndMessages(ON_AIRPLANE_MODE_CHANGED_TOKEN);
            repeatAirplaneRunnable = true;
        }
        mBluetoothLock.readLock().lock();
        try {
            if (mBluetooth != null) {
                //Unregister callback object
                synchronousUnregisterCallback(mBluetoothCallback, mContext.getAttributionSource());
            }
        } catch (RemoteException | TimeoutException e) {
            Log.e(TAG, "Unable to unregister", e);
        } finally {
            mBluetoothLock.readLock().unlock();
        }

        waitForState(Set.of(BluetoothAdapter.STATE_OFF));

        sendBluetoothServiceDownCallback();

        mHandler.removeMessages(MESSAGE_BLUETOOTH_STATE_CHANGE);
        mState = BluetoothAdapter.STATE_OFF;

        if (clearBle) {
            clearBleApps();
        }

        mEnable = false;

        // Send a Bluetooth Restart message to reenable bluetooth
        Message restartMsg = mHandler.obtainMessage(MESSAGE_RESTART_BLUETOOTH_SERVICE);
        mHandler.sendMessageDelayed(restartMsg, ERROR_RESTART_TIME_MS);

        if (repeatAirplaneRunnable) {
            onAirplaneModeChanged();
        }
    }

    private boolean isBluetoothDisallowed() {
        final long callingIdentity = Binder.clearCallingIdentity();
        try {
            return mContext.getSystemService(UserManager.class)
                    .hasUserRestrictionForUser(UserManager.DISALLOW_BLUETOOTH, UserHandle.SYSTEM);
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
    }

    /**
     * Disables BluetoothOppLauncherActivity component, so the Bluetooth sharing option is not
     * offered to the user if Bluetooth or sharing is disallowed. Puts the component to its default
     * state if Bluetooth is not disallowed.
     *
     * @param userHandle user to disable bluetooth sharing for
     * @param bluetoothSharingDisallowed whether bluetooth sharing is disallowed.
     */
    private void updateOppLauncherComponentState(UserHandle userHandle,
            boolean bluetoothSharingDisallowed) {
        try {
            int newState;
            if (bluetoothSharingDisallowed) {
                newState = PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            } else if (BluetoothProperties.isProfileOppEnabled().orElse(false)) {
                newState = PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
            } else {
                newState = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
            }

            // Bluetooth OPP activities that should always be enabled,
            // even when Bluetooth is turned OFF.
            List<String> baseBluetoothOppActivities = List.of(
                    // Base sharing activity
                    "com.android.bluetooth.opp.BluetoothOppLauncherActivity",
                    // BT enable activities
                    "com.android.bluetooth.opp.BluetoothOppBtEnableActivity",
                    "com.android.bluetooth.opp.BluetoothOppBtEnablingActivity",
                    "com.android.bluetooth.opp.BluetoothOppBtErrorActivity");

            PackageManager systemPackageManager = mContext.getPackageManager();
            PackageManager userPackageManager = mContext.createContextAsUser(userHandle, 0)
                                                        .getPackageManager();
            var allPackages = systemPackageManager.getPackagesForUid(Process.BLUETOOTH_UID);
            for (String candidatePackage : allPackages) {
                Log.v(TAG, "Searching package " + candidatePackage);
                PackageInfo packageInfo;
                try {
                    packageInfo = systemPackageManager.getPackageInfo(
                        candidatePackage,
                        PackageManager.PackageInfoFlags.of(
                            PackageManager.GET_ACTIVITIES
                            | PackageManager.MATCH_ANY_USER
                            | PackageManager.MATCH_UNINSTALLED_PACKAGES
                            | PackageManager.MATCH_DISABLED_COMPONENTS));
                } catch (PackageManager.NameNotFoundException e) {
                    // ignore, try next package
                    Log.e(TAG, "Could not find package " + candidatePackage);
                    continue;
                } catch (Exception e) {
                    Log.e(TAG, "Error while loading package" + e);
                    continue;
                }
                if (packageInfo.activities == null) {
                    continue;
                }
                for (var activity : packageInfo.activities) {
                    Log.v(TAG, "Checking activity " + activity.name);
                    if (baseBluetoothOppActivities.contains(activity.name)) {
                        for (String activityName : baseBluetoothOppActivities) {
                            userPackageManager.setComponentEnabledSetting(
                                    new ComponentName(candidatePackage, activityName),
                                    newState,
                                    PackageManager.DONT_KILL_APP
                            );
                        }
                        return;
                    }
                }
            }

            Log.e(TAG,
                    "Cannot toggle Bluetooth OPP activities, could not find them in any package");
        } catch (Exception e) {
            Log.e(TAG, "updateOppLauncherComponentState failed: " + e);
        }
    }

    private int getServiceRestartMs() {
        return (mErrorRecoveryRetryCounter + 1) * SERVICE_RESTART_TIME_MS;
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        if ((mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.DUMP) != PackageManager.PERMISSION_GRANTED)) {
            return;
        }
        if ((args.length > 0) && args[0].startsWith("--proto")) {
            dumpProto(fd);
            return;
        }
        String errorMsg = null;

        writer.println("Bluetooth Status");
        writer.println("  enabled: " + isEnabled());
        writer.println("  state: " + BluetoothAdapter.nameForState(mState));
        writer.println("  address: " + mAddress);
        writer.println("  name: " + mName);
        if (mEnable) {
            long onDuration = SystemClock.elapsedRealtime() - mLastEnabledTime;
            String onDurationString = String.format(Locale.US, "%02d:%02d:%02d.%03d",
                    (int) (onDuration / (1000 * 60 * 60)),
                    (int) ((onDuration / (1000 * 60)) % 60), (int) ((onDuration / 1000) % 60),
                    (int) (onDuration % 1000));
            writer.println("  time since enabled: " + onDurationString);
        }

        if (mActiveLogs.size() == 0) {
            writer.println("\nBluetooth never enabled!");
        } else {
            writer.println("\nEnable log:");
            for (ActiveLog log : mActiveLogs) {
                writer.println("  " + log);
            }
        }

        writer.println(
                "\nBluetooth crashed " + mCrashes + " time" + (mCrashes == 1 ? "" : "s"));
        if (mCrashes == CRASH_LOG_MAX_SIZE) {
            writer.println("(last " + CRASH_LOG_MAX_SIZE + ")");
        }
        for (Long time : mCrashTimestamps) {
            writer.println("  " + timeToLog(time));
        }

        writer.println("\n" + mBleApps.size() + " BLE app" + (mBleApps.size() == 1 ? "" : "s")
                + " registered");
        for (ClientDeathRecipient app : mBleApps.values()) {
            writer.println("  " + app.getPackageName());
        }

        writer.println("\nBluetoothManagerService:");
        writer.println("  mEnable:" + mEnable);
        writer.println("  mQuietEnable:" + mQuietEnable);
        writer.println("  mEnableExternal:" + mEnableExternal);
        writer.println("  mQuietEnableExternal:" + mQuietEnableExternal);

        writer.println("");
        writer.flush();
        if (args.length == 0) {
            // Add arg to produce output
            args = new String[1];
            args[0] = "--print";
        }

        if (mBluetoothBinder == null) {
            errorMsg = "Bluetooth Service not connected";
        } else {
            try {
                mBluetoothBinder.dump(fd, args);
            } catch (RemoteException re) {
                errorMsg = "RemoteException while dumping Bluetooth Service";
            }
        }
        if (errorMsg != null) {
            writer.println(errorMsg);
        }
    }

    private void dumpProto(FileDescriptor fd) {
        final ProtoOutputStream proto = new ProtoOutputStream(new FileOutputStream(fd));
        proto.write(BluetoothManagerServiceDumpProto.ENABLED, isEnabled());
        proto.write(BluetoothManagerServiceDumpProto.STATE, mState);
        proto.write(BluetoothManagerServiceDumpProto.STATE_NAME,
                BluetoothAdapter.nameForState(mState));
        proto.write(BluetoothManagerServiceDumpProto.ADDRESS, mAddress);
        proto.write(BluetoothManagerServiceDumpProto.NAME, mName);
        if (mEnable) {
            proto.write(BluetoothManagerServiceDumpProto.LAST_ENABLED_TIME_MS, mLastEnabledTime);
        }
        proto.write(BluetoothManagerServiceDumpProto.CURR_TIMESTAMP_MS,
                SystemClock.elapsedRealtime());
        for (ActiveLog log : mActiveLogs) {
            long token = proto.start(BluetoothManagerServiceDumpProto.ACTIVE_LOGS);
            log.dump(proto);
            proto.end(token);
        }
        proto.write(BluetoothManagerServiceDumpProto.NUM_CRASHES, mCrashes);
        proto.write(BluetoothManagerServiceDumpProto.CRASH_LOG_MAXED,
                mCrashes == CRASH_LOG_MAX_SIZE);
        for (Long time : mCrashTimestamps) {
            proto.write(BluetoothManagerServiceDumpProto.CRASH_TIMESTAMPS_MS, time);
        }
        proto.write(BluetoothManagerServiceDumpProto.NUM_BLE_APPS, mBleApps.size());
        for (ClientDeathRecipient app : mBleApps.values()) {
            proto.write(BluetoothManagerServiceDumpProto.BLE_APP_PACKAGE_NAMES,
                    app.getPackageName());
        }
        proto.flush();
    }

    private static String getEnableDisableReasonString(int reason) {
        switch (reason) {
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_APPLICATION_REQUEST:
                return "APPLICATION_REQUEST";
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_AIRPLANE_MODE:
                return "AIRPLANE_MODE";
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_DISALLOWED:
                return "DISALLOWED";
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_RESTARTED:
                return "RESTARTED";
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_START_ERROR:
                return "START_ERROR";
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_SYSTEM_BOOT:
                return "SYSTEM_BOOT";
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_CRASH:
                return "CRASH";
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_USER_SWITCH:
                return "USER_SWITCH";
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_RESTORE_USER_SETTING:
                return "RESTORE_USER_SETTING";
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_FACTORY_RESET:
                return "FACTORY_RESET";
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_INIT_FLAGS_CHANGED:
                return "INIT_FLAGS_CHANGED";
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_UNSPECIFIED:
            default: return "UNKNOWN[" + reason + "]";
        }
    }

    @SuppressLint("AndroidFrameworkRequiresPermission")
    private static boolean checkPermissionForDataDelivery(Context context, String permission,
            AttributionSource attributionSource, String message) {
        PermissionManager pm = context.getSystemService(PermissionManager.class);
        if (pm == null) {
            return false;
        }
        AttributionSource currentAttribution = new AttributionSource
                .Builder(context.getAttributionSource())
                .setNext(attributionSource)
                .build();
        final int result = pm.checkPermissionForDataDeliveryFromDataSource(permission,
                currentAttribution, message);
        if (result == PERMISSION_GRANTED) {
            return true;
        }

        final String msg = "Need " + permission + " permission for " + attributionSource + ": "
                + message;
        if (result == PERMISSION_HARD_DENIED) {
            throw new SecurityException(msg);
        } else {
            Log.w(TAG, msg);
            return false;
        }
    }

    /**
     * Returns true if the BLUETOOTH_CONNECT permission is granted for the calling app. Returns
     * false if the result is a soft denial. Throws SecurityException if the result is a hard
     * denial.
     *
     * <p>Should be used in situations where the app op should not be noted.
     */
    @SuppressLint("AndroidFrameworkRequiresPermission")
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public static boolean checkConnectPermissionForDataDelivery(
            Context context, AttributionSource attributionSource, String message) {
        return checkPermissionForDataDelivery(context, BLUETOOTH_CONNECT,
                attributionSource, message);
    }

    @Override
    public int handleShellCommand(@NonNull ParcelFileDescriptor in,
            @NonNull ParcelFileDescriptor out, @NonNull ParcelFileDescriptor err,
            @NonNull String[] args) {
        return new BluetoothShellCommand(this, mContext).exec(this, in.getFileDescriptor(),
                out.getFileDescriptor(), err.getFileDescriptor(), args);
    }

    // TODO(b/193460475): Remove when tooling supports SystemApi to public API.
    @SuppressLint("NewApi")
    static @NonNull Bundle getTempAllowlistBroadcastOptions() {
        final long duration = 10_000;
        final BroadcastOptions bOptions = BroadcastOptions.makeBasic();
        bOptions.setTemporaryAppAllowlist(duration,
                TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
                PowerExemptionManager.REASON_BLUETOOTH_BROADCAST, "");
        return bOptions.toBundle();
    }

    private ComponentName resolveSystemService(@NonNull Intent intent,
            @NonNull PackageManager pm, int flags) {
        List<ResolveInfo> results = pm.queryIntentServices(intent, flags);
        if (results == null) {
            return null;
        }
        ComponentName comp = null;
        for (int i = 0; i < results.size(); i++) {
            ResolveInfo ri = results.get(i);
            if ((ri.serviceInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                continue;
            }
            ComponentName foundComp = new ComponentName(ri.serviceInfo.applicationInfo.packageName,
                    ri.serviceInfo.name);
            if (comp != null) {
                throw new IllegalStateException("Multiple system services handle " + intent
                        + ": " + comp + ", " + foundComp);
            }
            comp = foundComp;
        }
        return comp;
    }

    private boolean isPrivileged(int pid, int uid) {
        return (mContext.checkPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED, pid, uid)
                == PackageManager.PERMISSION_GRANTED)
                || (mContext.getPackageManager().checkSignatures(uid, Process.SYSTEM_UID)
                == PackageManager.SIGNATURE_MATCH);
    }

    private Pair<UserHandle, ComponentName> getDeviceOwner() {
        DevicePolicyManager devicePolicyManager =
                mContext.getSystemService(DevicePolicyManager.class);
        if (devicePolicyManager == null) return null;
        long ident = Binder.clearCallingIdentity();
        UserHandle deviceOwnerUser = null;
        ComponentName deviceOwnerComponent = null;
        try {
            deviceOwnerUser = devicePolicyManager.getDeviceOwnerUser();
            deviceOwnerComponent = devicePolicyManager.getDeviceOwnerComponentOnAnyUser();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        if (deviceOwnerUser == null || deviceOwnerComponent == null
                || deviceOwnerComponent.getPackageName() == null) {
            return null;
        }
        return new Pair<>(deviceOwnerUser, deviceOwnerComponent);
    }

    private boolean isDeviceOwner(int uid, String packageName) {
        if (packageName == null) {
            Log.e(TAG, "isDeviceOwner: packageName is null, returning false");
            return false;
        }

        Pair<UserHandle, ComponentName> deviceOwner = getDeviceOwner();

        // no device owner
        if (deviceOwner == null) return false;

        return deviceOwner.first.equals(UserHandle.getUserHandleForUid(uid))
                && deviceOwner.second.getPackageName().equals(packageName);
    }

    private boolean isProfileOwner(int uid, String packageName) {
        Context userContext;
        try {
            userContext = mContext.createPackageContextAsUser(mContext.getPackageName(), 0,
                    UserHandle.getUserHandleForUid(uid));
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Unknown package name");
            return false;
        }
        if (userContext == null) {
            Log.e(TAG, "Unable to retrieve user context for " + uid);
            return false;
        }
        DevicePolicyManager devicePolicyManager =
                userContext.getSystemService(DevicePolicyManager.class);
        if (devicePolicyManager == null) {
            Log.w(TAG, "Error retrieving DPM service");
            return false;
        }
        return devicePolicyManager.isProfileOwnerApp(packageName);
    }

    public boolean isSystem(String packageName, int uid) {
        long ident = Binder.clearCallingIdentity();
        try {
            ApplicationInfo info = mContext.getPackageManager().getApplicationInfoAsUser(
                    packageName, 0, UserHandle.getUserHandleForUid(uid));
            return (info.flags & FLAGS_SYSTEM_APP) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * Sets Bluetooth HCI snoop log mode
     */
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    @Override
    public int setBtHciSnoopLogMode(int mode) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED,
                "Need BLUETOOTH_PRIVILEGED permission");
        final BluetoothProperties.snoop_log_mode_values snoopMode;

        switch (mode) {
            case BluetoothAdapter.BT_SNOOP_LOG_MODE_DISABLED:
                snoopMode = BluetoothProperties.snoop_log_mode_values.DISABLED;
                break;
            case BluetoothAdapter.BT_SNOOP_LOG_MODE_FILTERED:
                snoopMode = BluetoothProperties.snoop_log_mode_values.FILTERED;
                break;
            case BluetoothAdapter.BT_SNOOP_LOG_MODE_FULL:
                snoopMode = BluetoothProperties.snoop_log_mode_values.FULL;
                break;
            default:
                Log.e(TAG, "setBtHciSnoopLogMode: Not a valid mode:" + mode);
                return BluetoothStatusCodes.ERROR_BAD_PARAMETERS;
        }
        try {
            BluetoothProperties.snoop_log_mode(snoopMode);
        } catch (RuntimeException e) {
            Log.e(TAG, "setBtHciSnoopLogMode: Failed to set mode to " + mode + ": " + e);
            return BluetoothStatusCodes.ERROR_UNKNOWN;
        }
        return BluetoothStatusCodes.SUCCESS;
    }

    /**
     * Gets Bluetooth HCI snoop log mode
     */
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    @Override
    public int getBtHciSnoopLogMode() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED,
                "Need BLUETOOTH_PRIVILEGED permission");
        BluetoothProperties.snoop_log_mode_values mode = BluetoothProperties.snoop_log_mode()
                .orElse(BluetoothProperties.snoop_log_mode_values.DISABLED);
        if (mode == BluetoothProperties.snoop_log_mode_values.FILTERED) {
            return BluetoothAdapter.BT_SNOOP_LOG_MODE_FILTERED;
        } else if (mode == BluetoothProperties.snoop_log_mode_values.FULL) {
            return BluetoothAdapter.BT_SNOOP_LOG_MODE_FULL;
        }
        return BluetoothAdapter.BT_SNOOP_LOG_MODE_DISABLED;
    }

    /**
     * Check if BLE is supported by this platform
     * @param context current device context
     * @return true if BLE is supported, false otherwise
     */
    private static boolean isBleSupported(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
    }

    /**
     * Check if this is an automotive device
     * @param context current device context
     * @return true if this Android device is an automotive device, false otherwise
     */
    private static boolean isAutomotive(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
    }

    /**
     * Check if this is a watch device
     * @param context current device context
     * @return true if this Android device is a watch device, false otherwise
     */
    private static boolean isWatch(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH);
    }

    /**
     * Check if this is a TV device
     * @param context current device context
     * @return true if this Android device is a TV device, false otherwise
     */
    private static boolean isTv(Context context) {
        PackageManager pm = context.getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
                || pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }
}

