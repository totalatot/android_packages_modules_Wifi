/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi;

import static android.net.wifi.WifiManager.IFACE_IP_MODE_LOCAL_ONLY;
import static android.net.wifi.WifiManager.IFACE_IP_MODE_TETHERED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.LocationManager;
import android.net.wifi.SoftApCapability;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiManager;
import android.os.BatteryStatsManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.telephony.TelephonyManager;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IState;
import com.android.internal.util.Preconditions;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.wifi.util.WifiPermissionsUtil;
import com.android.wifi.resources.R;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * This class provides the implementation for different WiFi operating modes.
 */
public class ActiveModeWarden {
    private static final String TAG = "WifiActiveModeWarden";
    private static final String STATE_MACHINE_EXITED_STATE_NAME = "STATE_MACHINE_EXITED";

    // Holder for active mode managers
    private final ArraySet<ActiveModeManager> mActiveModeManagers;
    private final ArraySet<ModeChangeCallback> mCallbacks;
    // DefaultModeManager used to service API calls when there are no active client mode managers.
    private final DefaultClientModeManager mDefaultClientModeManager;
    private final WifiInjector mWifiInjector;
    private final Looper mLooper;
    private final Handler mHandler;
    private final Context mContext;
    private final BaseWifiDiagnostics mWifiDiagnostics;
    private final WifiSettingsStore mSettingsStore;
    private final FrameworkFacade mFacade;
    private final WifiPermissionsUtil mWifiPermissionsUtil;
    private final BatteryStatsManager mBatteryStatsManager;
    private final ScanRequestProxy mScanRequestProxy;
    private final WifiNative mWifiNative;
    private final WifiController mWifiController;

    private WifiManager.SoftApCallback mSoftApCallback;
    private WifiManager.SoftApCallback mLohsCallback;

    private boolean mCanRequestMoreClientModeManagers = false;
    private boolean mCanRequestMoreSoftApManagers = false;
    private boolean mIsShuttingdown = false;
    private boolean mVerboseLoggingEnabled = false;

    /**
     * Called from WifiServiceImpl to register a callback for notifications from SoftApManager
     */
    public void registerSoftApCallback(@NonNull WifiManager.SoftApCallback callback) {
        mSoftApCallback = callback;
    }

    /**
     * Called from WifiServiceImpl to register a callback for notifications from SoftApManager
     * for local-only hotspot.
     */
    public void registerLohsCallback(@NonNull WifiManager.SoftApCallback callback) {
        mLohsCallback = callback;
    }

    /**
     * Callbacks for indicating any mode manager changes to the rest of the system.
     */
    public interface ModeChangeCallback {
        /**
         * Invoked when new mode manager is added.
         *
         * @param activeModeManager Instance of {@link ActiveModeManager}.
         */
        void onActiveModeManagerAdded(@NonNull ActiveModeManager activeModeManager);

        /**
         * Invoked when a mode manager is removed.
         *
         * @param activeModeManager Instance of {@link ActiveModeManager}.
         */
        void onActiveModeManagerRemoved(@NonNull ActiveModeManager activeModeManager);

        /**
         * Invoked when an existing mode manager's role is changed.
         *
         * @param activeModeManager Instance of {@link ActiveModeManager}.
         */
        void onActiveModeManagerRoleChanged(@NonNull ActiveModeManager activeModeManager);
    }

    ActiveModeWarden(WifiInjector wifiInjector,
                     Looper looper,
                     WifiNative wifiNative,
                     DefaultClientModeManager defaultClientModeManager,
                     BatteryStatsManager batteryStatsManager,
                     BaseWifiDiagnostics wifiDiagnostics,
                     Context context,
                     WifiSettingsStore settingsStore,
                     FrameworkFacade facade,
                     WifiPermissionsUtil wifiPermissionsUtil) {
        mWifiInjector = wifiInjector;
        mLooper = looper;
        mHandler = new Handler(looper);
        mContext = context;
        mWifiDiagnostics = wifiDiagnostics;
        mSettingsStore = settingsStore;
        mFacade = facade;
        mWifiPermissionsUtil = wifiPermissionsUtil;
        mActiveModeManagers = new ArraySet<>();
        mCallbacks = new ArraySet<>();
        mDefaultClientModeManager = defaultClientModeManager;
        mBatteryStatsManager = batteryStatsManager;
        mScanRequestProxy = wifiInjector.getScanRequestProxy();
        mWifiNative = wifiNative;
        mWifiController = new WifiController();

        wifiNative.registerStatusListener(isReady -> {
            if (!isReady && !mIsShuttingdown) {
                mHandler.post(() -> {
                    Log.e(TAG, "One of the native daemons died. Triggering recovery");
                    wifiDiagnostics.captureBugReportData(
                            WifiDiagnostics.REPORT_REASON_WIFINATIVE_FAILURE);

                    // immediately trigger SelfRecovery if we receive a notice about an
                    // underlying daemon failure
                    // Note: SelfRecovery has a circular dependency with ActiveModeWarden and is
                    // instantiated after ActiveModeWarden, so use WifiInjector to get the instance
                    // instead of directly passing in SelfRecovery in the constructor.
                    mWifiInjector.getSelfRecovery().trigger(SelfRecovery.REASON_WIFINATIVE_FAILURE);
                });
            }
        });

        wifiNative.registerClientInterfaceAvailabilityListener(
                (isAvailable) -> mHandler.post(() -> {
                    mCanRequestMoreClientModeManagers = isAvailable;
                }));
        wifiNative.registerSoftApInterfaceAvailabilityListener(
                (isAvailable) -> mHandler.post(() -> {
                    mCanRequestMoreSoftApManagers = isAvailable;
                }));
    }

    private void invokeOnAddedCallbacks(@NonNull ActiveModeManager activeModeManager) {
        for (ModeChangeCallback callback : mCallbacks) {
            callback.onActiveModeManagerAdded(activeModeManager);
        }
    }

    private void invokeOnRemovedCallbacks(@NonNull ActiveModeManager activeModeManager) {
        for (ModeChangeCallback callback : mCallbacks) {
            callback.onActiveModeManagerRemoved(activeModeManager);
        }
    }

    private void invokeOnRoleChangedCallbacks(@NonNull ActiveModeManager activeModeManager) {
        for (ModeChangeCallback callback : mCallbacks) {
            callback.onActiveModeManagerRoleChanged(activeModeManager);
        }
    }

    /**
     * Enable verbose logging.
     */
    public void enableVerboseLogging(boolean verbose) {
        mVerboseLoggingEnabled = verbose;
        for (ActiveModeManager modeManager : mActiveModeManagers) {
            modeManager.enableVerboseLogging(verbose);
        }
    }

    /**
     * Register for mode change callbacks.
     */
    public void registerModeChangeCallback(@NonNull ModeChangeCallback callback) {
        mCallbacks.add(Objects.requireNonNull(callback));
    }

    /**
     * Unregister mode change callback.
     */
    public void unregisterModeChangeCallback(@NonNull ModeChangeCallback callback) {
        mCallbacks.remove(Objects.requireNonNull(callback));
    }

    /**
     * Notify that device is shutting down
     * Keep it simple and don't add collection access codes
     * to avoid concurrentModificationException when it is directly called from a different thread
     */
    public void notifyShuttingDown() {
        mIsShuttingdown = true;
    }

    /**
     * @return Returns whether we can create more client mode managers or not.
     */
    public boolean canRequestMoreClientModeManagers() {
        return mCanRequestMoreClientModeManagers;
    }

    /**
     * @return Returns whether we can create more SoftAp managers or not.
     */
    public boolean canRequestMoreSoftApManagers() {
        return mCanRequestMoreSoftApManagers;
    }

    /**
     * @return Returns whether the device can support at least one concurrent client mode manager &
     * softap manager.
     */
    public boolean isStaApConcurrencySupported() {
        return mWifiNative.isStaApConcurrencySupported();
    }

    /** Begin listening to broadcasts and start the internal state machine. */
    public void start() {
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // Location mode has been toggled...  trigger with the scan change
                // update to make sure we are in the correct mode
                scanAlwaysModeChanged();
            }
        }, new IntentFilter(LocationManager.MODE_CHANGED_ACTION));
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (mSettingsStore.handleAirplaneModeToggled()) {
                    airplaneModeToggled();
                }
            }
        }, new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED));
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean emergencyMode =
                        intent.getBooleanExtra(TelephonyManager.EXTRA_PHONE_IN_ECM_STATE, false);
                emergencyCallbackModeChanged(emergencyMode);
            }
        }, new IntentFilter(TelephonyManager.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED));
        boolean trackEmergencyCallState = mContext.getResources().getBoolean(
                R.bool.config_wifi_turn_off_during_emergency_call);
        if (trackEmergencyCallState) {
            mContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    boolean inCall = intent.getBooleanExtra(
                            TelephonyManager.EXTRA_PHONE_IN_EMERGENCY_CALL, false);
                    emergencyCallStateChanged(inCall);
                }
            }, new IntentFilter(TelephonyManager.ACTION_EMERGENCY_CALL_STATE_CHANGED));
        }

        mWifiController.start();
    }

    /** Disable Wifi for recovery purposes. */
    public void recoveryDisableWifi() {
        mWifiController.sendMessage(WifiController.CMD_RECOVERY_DISABLE_WIFI);
    }

    /**
     * Restart Wifi for recovery purposes.
     * @param reason One of {@link SelfRecovery.RecoveryReason}
     */
    public void recoveryRestartWifi(@SelfRecovery.RecoveryReason int reason) {
        mWifiController.sendMessage(WifiController.CMD_RECOVERY_RESTART_WIFI, reason);
    }

    /** Wifi has been toggled. */
    public void wifiToggled() {
        mWifiController.sendMessage(WifiController.CMD_WIFI_TOGGLED);
    }

    /** Airplane Mode has been toggled. */
    public void airplaneModeToggled() {
        mWifiController.sendMessage(WifiController.CMD_AIRPLANE_TOGGLED);
    }

    /** Starts SoftAp. */
    public void startSoftAp(SoftApModeConfiguration softApConfig) {
        mWifiController.sendMessage(WifiController.CMD_SET_AP, 1, 0, softApConfig);
    }

    /** Stop SoftAp. */
    public void stopSoftAp(int mode) {
        mWifiController.sendMessage(WifiController.CMD_SET_AP, 0, mode);
    }

    /** Update SoftAp Capability. */
    public void updateSoftApCapability(SoftApCapability capability) {
        mWifiController.sendMessage(WifiController.CMD_UPDATE_AP_CAPABILITY, capability);
    }

    /** Update SoftAp Configuration. */
    public void updateSoftApConfiguration(SoftApConfiguration config) {
        mWifiController.sendMessage(WifiController.CMD_UPDATE_AP_CONFIG, config);
    }

    /** Emergency Callback Mode has changed. */
    private void emergencyCallbackModeChanged(boolean isInEmergencyCallbackMode) {
        mWifiController.sendMessage(
                WifiController.CMD_EMERGENCY_MODE_CHANGED, isInEmergencyCallbackMode ? 1 : 0);
    }

    /** Emergency Call state has changed. */
    private void emergencyCallStateChanged(boolean isInEmergencyCall) {
        mWifiController.sendMessage(
                WifiController.CMD_EMERGENCY_CALL_STATE_CHANGED, isInEmergencyCall ? 1 : 0);
    }

    /** Scan always mode has changed. */
    public void scanAlwaysModeChanged() {
        mWifiController.sendMessage(WifiController.CMD_SCAN_ALWAYS_MODE_CHANGED);
    }

    /**
     * Listener to request a ModeManager instance for a particular operation.
     */
    public interface ExternalClientModeManagerRequestListener {
        /**
         * Returns an instance of ClientModeManager for the client for use.
         */
        void onAnswer(ClientModeManager modeManager);
    }

    /**
     * Request a new local only client manager.
     */
    public void requestLocalOnlyClientModeManager(
            @NonNull ExternalClientModeManagerRequestListener listener) {
        mWifiController.sendMessage(
                WifiController.CMD_ADD_LOCAL_ONLY_CLIENT_MODE_MANAGER,
                Objects.requireNonNull(listener));
    }

    /**
     * Remove local only client manager.
     */
    public void removeLocalOnlyClientModeManager(ClientModeManager clientModeManager) {
        mWifiController.sendMessage(
                WifiController.CMD_REMOVE_LOCAL_ONLY_CLIENT_MODE_MANAGER, clientModeManager);
    }

    /**
     * Returns primary client mode manager if any, else returns an instance of
     * {@link ClientModeManager}.
     * This mode manager can be the default route on the device & will handle all external API
     * calls.
     * @return Instance of {@link ClientModeManager}.
     */
    @NonNull
    public ClientModeManager getPrimaryClientModeManager() {
        ClientModeManager cm = getClientModeManagerInRole(ActiveModeManager.ROLE_CLIENT_PRIMARY);
        if (cm != null) return cm;
        // If there is no primary client manager, return the default one.
        return mDefaultClientModeManager;
    }

    /**
     * Returns all instances of ClientModeManager in
     * {@link ActiveModeManager#CLIENT_INTERNET_CONNECTIVITY_ROLES} roles.
     * @return List of {@link ClientModeManager}.
     */
    @NonNull
    public List<ClientModeManager> getInternetConnectivityClientModeManagers() {
        return getClientModeManagersInRoles(ActiveModeManager.CLIENT_INTERNET_CONNECTIVITY_ROLES);
    }

    /**
     * Returns scan only client mode manager, if any.
     * This mode manager will only allow scanning.
     * @return Instance of {@link ClientModeManager} or null if none present.
     */
    @Nullable
    public ClientModeManager getScanOnlyClientModeManager() {
        return getClientModeManagerInRole(ActiveModeManager.ROLE_CLIENT_SCAN_ONLY);
    }

    /**
     * Returns tethered softap manager, if any.
     * @return Instance of {@link SoftApManager} or null if none present.
     */
    @Nullable
    public SoftApManager getTetheredSoftApManager() {
        return getSoftApManagerInRole(ActiveModeManager.ROLE_SOFTAP_TETHERED);
    }

    /**
     * Returns LOHS softap manager, if any.
     * @return Instance of {@link SoftApManager} or null if none present.
     */
    @Nullable
    public SoftApManager getLocalOnlySoftApManager() {
        return getSoftApManagerInRole(ActiveModeManager.ROLE_SOFTAP_LOCAL_ONLY);
    }

    private boolean hasAnyModeManager() {
        return !mActiveModeManagers.isEmpty();
    }

    /**
     * @return true if any mode managers in specified role.
     */
    private boolean hasAnyModeManagerInRole(@ActiveModeManager.Role int role) {
        for (ActiveModeManager manager : mActiveModeManagers) {
            if (manager.getRole() == role) return true;
        }
        return false;
    }

    /**
     * @return true if any mode managers in one of the specified roles.
     */
    private boolean hasAnyModeManagerInOneOfRoles(Collection<Integer> rolesList) {
        for (ActiveModeManager manager : mActiveModeManagers) {
            if (rolesList.contains(manager.getRole())) return true;
        }
        return false;
    }

    private boolean hasAnyClientModeManager() {
        return hasAnyModeManagerInOneOfRoles(ActiveModeManager.CLIENT_ROLES);
    }

    private boolean hasAnyClientModeManagerInConnectivityRole() {
        return hasAnyModeManagerInOneOfRoles(ActiveModeManager.CLIENT_CONNECTIVITY_ROLES);
    }

    private boolean hasAnySoftApManager() {
        return hasAnyModeManagerInOneOfRoles(ActiveModeManager.SOFTAP_ROLES);
    }

    /**
     * @return true if any mode manager is stopping
     */
    private boolean hasAnyModeManagerStopping() {
        for (ActiveModeManager manager : mActiveModeManagers) {
            if (manager.isStopping()) return true;
        }
        return false;
    }

    /**
     * @return true if all the client mode managers are in scan only role,
     * false if there are no client mode managers present or if any of them are not in scan only
     * role.
     */
    private boolean areAllClientModeManagersInScanOnlyRole() {
        boolean hasAnyClientModeManager = false;
        for (ActiveModeManager manager : mActiveModeManagers) {
            if (ActiveModeManager.CLIENT_ROLES.contains(manager.getRole())) {
                hasAnyClientModeManager = true;
                if (manager.getRole() != ActiveModeManager.ROLE_CLIENT_SCAN_ONLY) return false;
            }
        }
        return hasAnyClientModeManager;
    }

    @Nullable
    private ClientModeManager getClientModeManagerInRole(@ActiveModeManager.Role int role) {
        Preconditions.checkArgument(ActiveModeManager.CLIENT_ROLES.contains(role));

        for (ActiveModeManager manager : mActiveModeManagers) {
            if (manager.getRole() == role) return (ClientModeManager) manager;
        }
        return null;
    }

    @NonNull
    private List<ClientModeManager> getClientModeManagersInRoles(Collection<Integer> rolesList) {
        List<ClientModeManager> modeManagers = new ArrayList<>();
        for (ActiveModeManager manager : mActiveModeManagers) {
            if (rolesList.contains(manager.getRole())) {
                modeManagers.add((ClientModeManager) manager);
            }
        }
        return modeManagers;
    }

    @Nullable
    private SoftApManager getSoftApManagerInRole(@ActiveModeManager.Role int role) {
        Preconditions.checkArgument(ActiveModeManager.SOFTAP_ROLES.contains(role));

        for (ActiveModeManager manager : mActiveModeManagers) {
            if (manager.getRole() == role) return (SoftApManager) manager;
        }
        return null;
    }

    private @ActiveModeManager.Role int getRoleForSoftApIpMode(int ipMode) {
        return ipMode == IFACE_IP_MODE_TETHERED
                ? ActiveModeManager.ROLE_SOFTAP_TETHERED : ActiveModeManager.ROLE_SOFTAP_LOCAL_ONLY;
    }

    /**
     * Method to enable soft ap for wifi hotspot.
     *
     * The supplied SoftApModeConfiguration includes the target softap WifiConfiguration (or null if
     * the persisted config is to be used) and the target operating mode (ex,
     * {@link WifiManager#IFACE_IP_MODE_TETHERED} {@link WifiManager#IFACE_IP_MODE_LOCAL_ONLY}).
     *
     * @param softApConfig SoftApModeConfiguration for the hostapd softap
     */
    private void startSoftApModeManager(@NonNull SoftApModeConfiguration softApConfig) {
        Log.d(TAG, "Starting SoftApModeManager config = "
                + softApConfig.getSoftApConfiguration());
        Preconditions.checkState(softApConfig.getTargetMode() == IFACE_IP_MODE_LOCAL_ONLY
                || softApConfig.getTargetMode() == IFACE_IP_MODE_TETHERED);

        WifiManager.SoftApCallback callback =
                softApConfig.getTargetMode() == IFACE_IP_MODE_LOCAL_ONLY
                        ? mLohsCallback : mSoftApCallback;
        SoftApListener listener = new SoftApListener();
        ActiveModeManager manager =
                mWifiInjector.makeSoftApManager(listener, callback, softApConfig);
        listener.setActiveModeManager(manager);
        manager.start();
        manager.setRole(getRoleForSoftApIpMode(softApConfig.getTargetMode()));
        manager.enableVerboseLogging(mVerboseLoggingEnabled);
        mActiveModeManagers.add(manager);
    }

    /**
     * Method to stop all soft ap for the specified mode.
     *
     * This method will stop any active softAp mode managers.
     *
     * @param ipMode the operating mode of APs to bring down (ex,
     *             {@link WifiManager#IFACE_IP_MODE_TETHERED} or
     *             {@link WifiManager#IFACE_IP_MODE_LOCAL_ONLY}).
     *             Use {@link WifiManager#IFACE_IP_MODE_UNSPECIFIED} to stop all APs.
     */
    private void stopSoftApModeManagers(int ipMode) {
        Log.d(TAG, "Shutting down all softap mode managers in mode " + ipMode);
        for (ActiveModeManager manager : mActiveModeManagers) {
            if (!(manager instanceof SoftApManager)) continue;
            SoftApManager softApManager = (SoftApManager) manager;

            if (ipMode == WifiManager.IFACE_IP_MODE_UNSPECIFIED
                    || getRoleForSoftApIpMode(ipMode) == softApManager.getRole()) {
                softApManager.stop();
            }
        }
    }

    private void updateCapabilityToSoftApModeManager(SoftApCapability capability) {
        for (ActiveModeManager manager : mActiveModeManagers) {
            if (!(manager instanceof SoftApManager)) continue;
            SoftApManager softApManager = (SoftApManager) manager;
            softApManager.updateCapability(capability);
        }
    }

    private void updateConfigurationToSoftApModeManager(SoftApConfiguration config) {
        for (ActiveModeManager manager : mActiveModeManagers) {
            if (!(manager instanceof SoftApManager)) continue;
            SoftApManager softApManager = (SoftApManager) manager;
            softApManager.updateConfiguration(config);
        }
    }

    /**
     * Method to enable a new primary client mode manager.
     */
    private boolean startPrimaryOrScanOnlyClientModeManager() {
        Log.d(TAG, "Starting primary ClientModeManager");
        ClientListener listener = new ClientListener();
        ClientModeManager manager = mWifiInjector.makeClientModeManager(listener);
        listener.setActiveModeManager(manager);
        manager.start();
        if (!switchPrimaryOrScanOnlyClientModeManagerRole(manager)) {
            return false;
        }
        manager.enableVerboseLogging(mVerboseLoggingEnabled);
        mActiveModeManagers.add(manager);
        return true;
    }

    /**
     * Method to stop all client mode mangers.
     */
    private void stopAllClientModeManagers() {
        Log.d(TAG, "Shutting down all client mode managers");
        for (ActiveModeManager manager : mActiveModeManagers) {
            if (!(manager instanceof ClientModeManager)) continue;
            ClientModeManager clientModeManager = (ClientModeManager) manager;
            clientModeManager.stop();
        }
    }

    /**
     * Method to switch all client mode manager mode of operation (from ScanOnly To Connect &
     * vice-versa) based on the toggle state.
     */
    private boolean switchAllPrimaryOrScanOnlyClientModeManagers() {
        Log.d(TAG, "Switching all client mode managers");
        for (ActiveModeManager manager : mActiveModeManagers) {
            if (!(manager instanceof ClientModeManager)) continue;
            if (manager.getRole() != ActiveModeManager.ROLE_CLIENT_PRIMARY
                    && manager.getRole() != ActiveModeManager.ROLE_CLIENT_SCAN_ONLY) {
                continue;
            }
            ClientModeManager clientModeManager = (ClientModeManager) manager;
            if (!switchPrimaryOrScanOnlyClientModeManagerRole(clientModeManager)) {
                return false;
            }
        }
        updateBatteryStats();
        return true;
    }

    /**
     * Method to switch a client mode manager mode of operation (from ScanOnly To Connect &
     * vice-versa) based on the toggle state.
     */
    private boolean switchPrimaryOrScanOnlyClientModeManagerRole(
            @NonNull ClientModeManager modeManager) {
        if (mSettingsStore.isWifiToggleEnabled()) {
            modeManager.setRole(ActiveModeManager.ROLE_CLIENT_PRIMARY);
        } else if (checkScanOnlyModeAvailable()) {
            modeManager.setRole(ActiveModeManager.ROLE_CLIENT_SCAN_ONLY);
        } else {
            Log.e(TAG, "Something is wrong, no client mode toggles enabled");
            return false;
        }
        return true;
    }

    /**
     * Method to enable a new local only client mode manager.
     */
    private boolean startLocalOnlyClientModeManager(
            @NonNull ExternalClientModeManagerRequestListener externalRequestListener) {
        Log.d(TAG, "Starting local only ClientModeManager");
        ClientListener listener = new ClientListener(externalRequestListener);
        ClientModeManager manager = mWifiInjector.makeClientModeManager(listener);
        listener.setActiveModeManager(manager);
        manager.start();
        manager.setRole(ActiveModeManager.ROLE_CLIENT_LOCAL_ONLY);
        manager.enableVerboseLogging(mVerboseLoggingEnabled);
        mActiveModeManagers.add(manager);
        return true;
    }

    /**
     * Method to stop local only client mode manger.
     */
    private void stopLocalOnlyClientModeManager(ClientModeManager clientModeManager) {
        // If this is not a local only client mode manager, ignore.
        if (clientModeManager.getRole() != ActiveModeManager.ROLE_CLIENT_LOCAL_ONLY) return;
        Log.d(TAG, "Shutting down local only client mode manager");
        clientModeManager.stop();
    }

    /**
     * Method to stop all active modes, for example, when toggling airplane mode.
     */
    private void shutdownWifi() {
        Log.d(TAG, "Shutting down all mode managers");
        for (ActiveModeManager manager : mActiveModeManagers) {
            manager.stop();
        }
    }

    /**
     * Dump current state for active mode managers.
     *
     * Must be called from the main Wifi thread.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dump of " + TAG);
        pw.println("Current wifi mode: " + getCurrentMode());
        pw.println("NumActiveModeManagers: " + mActiveModeManagers.size());
        mWifiController.dump(fd, pw, args);
        for (ActiveModeManager manager : mActiveModeManagers) {
            manager.dump(fd, pw, args);
        }
    }

    @VisibleForTesting
    String getCurrentMode() {
        IState state = mWifiController.getCurrentState();
        return state == null ? STATE_MACHINE_EXITED_STATE_NAME : state.getName();
    }

    @VisibleForTesting
    Collection<ActiveModeManager> getActiveModeManagers() {
        return new ArraySet<>(mActiveModeManagers);
    }

    @VisibleForTesting
    boolean isInEmergencyMode() {
        IState state = mWifiController.getCurrentState();
        return ((WifiController.BaseState) state).isInEmergencyMode();
    }

    /**
     *  Helper class to wrap the ActiveModeManager callback objects.
     */
    private static class ModeCallback {
        private ActiveModeManager mActiveManager;

        void setActiveModeManager(ActiveModeManager manager) {
            mActiveManager = manager;
        }

        ActiveModeManager getActiveModeManager() {
            return mActiveManager;
        }
    }

    private void updateBatteryStats() {
        updateBatteryStatsWifiState(hasAnyModeManager());
        if (areAllClientModeManagersInScanOnlyRole()) {
            updateBatteryStatsScanModeActive();
        }
    }

    private class SoftApListener extends ModeCallback implements ActiveModeManager.Listener {
        @Override
        public void onStarted() {
            updateBatteryStats();
            invokeOnAddedCallbacks(getActiveModeManager());
        }

        @Override
        public void onRoleChanged() {
            Log.w(TAG, "Role switched received on SoftApManager unexpectedly");
        }

        @Override
        public void onStopped() {
            mActiveModeManagers.remove(getActiveModeManager());
            updateBatteryStats();
            mWifiController.sendMessage(WifiController.CMD_AP_STOPPED);
            invokeOnRemovedCallbacks(getActiveModeManager());
        }

        @Override
        public void onStartFailure() {
            mActiveModeManagers.remove(getActiveModeManager());
            updateBatteryStats();
            mWifiController.sendMessage(WifiController.CMD_AP_START_FAILURE);
        }
    }

    private class ClientListener extends ModeCallback implements ActiveModeManager.Listener {
        private final ExternalClientModeManagerRequestListener mExternalRequestListener;

        ClientListener() {
            this(null);
        }

        ClientListener(
                @Nullable ExternalClientModeManagerRequestListener externalRequestListener) {
            mExternalRequestListener = externalRequestListener;
        }

        @Override
        public void onStarted() {
            updateClientScanMode();
            updateBatteryStats();
            if (mExternalRequestListener != null) {
                ClientModeManager clientModeManager = (ClientModeManager) getActiveModeManager();
                mExternalRequestListener.onAnswer(clientModeManager);
            }
            invokeOnAddedCallbacks(getActiveModeManager());
        }

        @Override
        public void onRoleChanged() {
            updateClientScanMode();
            updateBatteryStats();
            invokeOnRoleChangedCallbacks(getActiveModeManager());
        }

        @Override
        public void onStopped() {
            mActiveModeManagers.remove(getActiveModeManager());
            updateClientScanMode();
            updateBatteryStats();
            mWifiController.sendMessage(WifiController.CMD_STA_STOPPED);
            invokeOnRemovedCallbacks(getActiveModeManager());
        }

        @Override
        public void onStartFailure() {
            mActiveModeManagers.remove(getActiveModeManager());
            updateClientScanMode();
            updateBatteryStats();
            mWifiController.sendMessage(WifiController.CMD_STA_START_FAILURE);
        }
    }

    // Update the scan state based on all active mode managers.
    private void updateClientScanMode() {
        boolean scanEnabled = hasAnyClientModeManager();
        boolean scanningForHiddenNetworksEnabled;

        if (mContext.getResources().getBoolean(R.bool.config_wifiScanHiddenNetworksScanOnlyMode)) {
            scanningForHiddenNetworksEnabled = hasAnyClientModeManager();
        } else {
            scanningForHiddenNetworksEnabled = hasAnyClientModeManagerInConnectivityRole();
        }
        mScanRequestProxy.enableScanning(scanEnabled, scanningForHiddenNetworksEnabled);
    }

    /**
     *  Helper method to report wifi state as on/off (doesn't matter which mode).
     *
     *  @param enabled boolean indicating that some mode has been turned on or off
     */
    private void updateBatteryStatsWifiState(boolean enabled) {
        if (enabled) {
            if (mActiveModeManagers.size() == 1) {
                // only report wifi on if we haven't already
                mBatteryStatsManager.reportWifiOn();
            }
        } else {
            if (mActiveModeManagers.size() == 0) {
                // only report if we don't have any active modes
                mBatteryStatsManager.reportWifiOff();
            }
        }
    }

    private void updateBatteryStatsScanModeActive() {
        mBatteryStatsManager.reportWifiState(BatteryStatsManager.WIFI_STATE_OFF_SCANNING, null);
    }

    private boolean checkScanOnlyModeAvailable() {
        return mWifiPermissionsUtil.isLocationModeEnabled()
                && mSettingsStore.isScanAlwaysAvailable();
    }

    /**
     * WifiController is the class used to manage wifi state for various operating
     * modes (normal, airplane, wifi hotspot, etc.).
     */
    private class WifiController extends StateMachine {
        private static final String TAG = "WifiController";

        // Maximum limit to use for timeout delay if the value from overlay setting is too large.
        private static final int MAX_RECOVERY_TIMEOUT_DELAY_MS = 4000;

        private static final int BASE = Protocol.BASE_WIFI_CONTROLLER;

        static final int CMD_EMERGENCY_MODE_CHANGED                 = BASE + 1;
        static final int CMD_SCAN_ALWAYS_MODE_CHANGED               = BASE + 7;
        static final int CMD_WIFI_TOGGLED                           = BASE + 8;
        static final int CMD_AIRPLANE_TOGGLED                       = BASE + 9;
        static final int CMD_SET_AP                                 = BASE + 10;
        static final int CMD_EMERGENCY_CALL_STATE_CHANGED           = BASE + 14;
        static final int CMD_AP_STOPPED                             = BASE + 15;
        static final int CMD_STA_START_FAILURE                      = BASE + 16;
        // Command used to trigger a wifi stack restart when in active mode
        static final int CMD_RECOVERY_RESTART_WIFI                  = BASE + 17;
        // Internal command used to complete wifi stack restart
        private static final int CMD_RECOVERY_RESTART_WIFI_CONTINUE = BASE + 18;
        // Command to disable wifi when SelfRecovery is throttled or otherwise not doing full
        // recovery
        static final int CMD_RECOVERY_DISABLE_WIFI                  = BASE + 19;
        static final int CMD_STA_STOPPED                            = BASE + 20;
        static final int CMD_DEFERRED_RECOVERY_RESTART_WIFI         = BASE + 22;
        static final int CMD_AP_START_FAILURE                       = BASE + 23;
        static final int CMD_UPDATE_AP_CAPABILITY                   = BASE + 24;
        static final int CMD_UPDATE_AP_CONFIG                       = BASE + 25;
        static final int CMD_ADD_LOCAL_ONLY_CLIENT_MODE_MANAGER     = BASE + 26;
        static final int CMD_REMOVE_LOCAL_ONLY_CLIENT_MODE_MANAGER  = BASE + 27;

        private final EnabledState mEnabledState = new EnabledState();
        private final DisabledState mDisabledState = new DisabledState();

        private boolean mIsInEmergencyCall = false;
        private boolean mIsInEmergencyCallbackMode = false;

        WifiController() {
            super(TAG, mLooper);

            DefaultState defaultState = new DefaultState();
            addState(defaultState); {
                addState(mDisabledState, defaultState);
                addState(mEnabledState, defaultState);
            }

            setLogRecSize(100);
            setLogOnlyTransitions(false);

        }

        @Override
        public void start() {
            boolean isAirplaneModeOn = mSettingsStore.isAirplaneModeOn();
            boolean isWifiEnabled = mSettingsStore.isWifiToggleEnabled();
            boolean isScanningAlwaysAvailable = mSettingsStore.isScanAlwaysAvailable();
            boolean isLocationModeActive = mWifiPermissionsUtil.isLocationModeEnabled();

            log("isAirplaneModeOn = " + isAirplaneModeOn
                    + ", isWifiEnabled = " + isWifiEnabled
                    + ", isScanningAvailable = " + isScanningAlwaysAvailable
                    + ", isLocationModeActive = " + isLocationModeActive);

            if (shouldEnableSta()) {
                startPrimaryOrScanOnlyClientModeManager();
                setInitialState(mEnabledState);
            } else {
                setInitialState(mDisabledState);
            }

            // Initialize the lower layers before we start.
            mWifiNative.initialize();
            super.start();
        }

        private int readWifiRecoveryDelay() {
            int recoveryDelayMillis = mContext.getResources().getInteger(
                    R.integer.config_wifi_framework_recovery_timeout_delay);
            if (recoveryDelayMillis > MAX_RECOVERY_TIMEOUT_DELAY_MS) {
                recoveryDelayMillis = MAX_RECOVERY_TIMEOUT_DELAY_MS;
                Log.w(TAG, "Overriding timeout delay with maximum limit value");
            }
            return recoveryDelayMillis;
        }

        abstract class BaseState extends State {
            @VisibleForTesting
            boolean isInEmergencyMode() {
                return mIsInEmergencyCall || mIsInEmergencyCallbackMode;
            }

            private void updateEmergencyMode(Message msg) {
                if (msg.what == CMD_EMERGENCY_CALL_STATE_CHANGED) {
                    mIsInEmergencyCall = msg.arg1 == 1;
                } else if (msg.what == CMD_EMERGENCY_MODE_CHANGED) {
                    mIsInEmergencyCallbackMode = msg.arg1 == 1;
                }
            }

            private void enterEmergencyMode() {
                stopSoftApModeManagers(WifiManager.IFACE_IP_MODE_UNSPECIFIED);
                boolean configWiFiDisableInECBM = mFacade.getConfigWiFiDisableInECBM(mContext);
                log("WifiController msg getConfigWiFiDisableInECBM " + configWiFiDisableInECBM);
                if (configWiFiDisableInECBM) {
                    shutdownWifi();
                }
            }

            private void exitEmergencyMode() {
                if (shouldEnableSta()) {
                    startPrimaryOrScanOnlyClientModeManager();
                    transitionTo(mEnabledState);
                } else {
                    transitionTo(mDisabledState);
                }
            }

            @Override
            public final boolean processMessage(Message msg) {
                // potentially enter emergency mode
                if (msg.what == CMD_EMERGENCY_CALL_STATE_CHANGED
                        || msg.what == CMD_EMERGENCY_MODE_CHANGED) {
                    boolean wasInEmergencyMode = isInEmergencyMode();
                    updateEmergencyMode(msg);
                    boolean isInEmergencyMode = isInEmergencyMode();
                    if (!wasInEmergencyMode && isInEmergencyMode) {
                        enterEmergencyMode();
                    } else if (wasInEmergencyMode && !isInEmergencyMode) {
                        exitEmergencyMode();
                    }
                    return HANDLED;
                } else if (isInEmergencyMode()) {
                    // already in emergency mode, drop all messages other than mode stop messages
                    // triggered by emergency mode start.
                    if (msg.what == CMD_STA_STOPPED || msg.what == CMD_AP_STOPPED) {
                        if (!hasAnyModeManager()) {
                            log("No active mode managers, return to DisabledState.");
                            transitionTo(mDisabledState);
                        }
                    }
                    return HANDLED;
                }
                // not in emergency mode, process messages normally
                return processMessageFiltered(msg);
            }

            protected abstract boolean processMessageFiltered(Message msg);
        }

        class DefaultState extends State {
            @Override
            public boolean processMessage(Message msg) {
                switch (msg.what) {
                    case CMD_SCAN_ALWAYS_MODE_CHANGED:
                    case CMD_WIFI_TOGGLED:
                    case CMD_STA_STOPPED:
                    case CMD_STA_START_FAILURE:
                    case CMD_AP_STOPPED:
                    case CMD_AP_START_FAILURE:
                    case CMD_RECOVERY_RESTART_WIFI:
                    case CMD_RECOVERY_RESTART_WIFI_CONTINUE:
                    case CMD_DEFERRED_RECOVERY_RESTART_WIFI:
                    case CMD_ADD_LOCAL_ONLY_CLIENT_MODE_MANAGER:
                    case CMD_REMOVE_LOCAL_ONLY_CLIENT_MODE_MANAGER:
                        break;
                    case CMD_RECOVERY_DISABLE_WIFI:
                        log("Recovery has been throttled, disable wifi");
                        shutdownWifi();
                        // onStopped will move the state machine to "DisabledState".
                        break;
                    case CMD_AIRPLANE_TOGGLED:
                        if (mSettingsStore.isAirplaneModeOn()) {
                            log("Airplane mode toggled, shutdown all modes");
                            shutdownWifi();
                            // onStopped will move the state machine to "DisabledState".
                        } else {
                            log("Airplane mode disabled, determine next state");
                            if (shouldEnableSta()) {
                                startPrimaryOrScanOnlyClientModeManager();
                                transitionTo(mEnabledState);
                            }
                            // wifi should remain disabled, do not need to transition
                        }
                        break;
                    case CMD_UPDATE_AP_CAPABILITY:
                        updateCapabilityToSoftApModeManager((SoftApCapability) msg.obj);
                        break;
                    case CMD_UPDATE_AP_CONFIG:
                        updateConfigurationToSoftApModeManager((SoftApConfiguration) msg.obj);
                        break;
                    default:
                        throw new RuntimeException("WifiController.handleMessage " + msg.what);
                }
                return HANDLED;
            }
        }

        private boolean shouldEnableSta() {
            return mSettingsStore.isWifiToggleEnabled() || checkScanOnlyModeAvailable();
        }

        class DisabledState extends BaseState {
            @Override
            public void enter() {
                log("DisabledState.enter()");
                super.enter();
                if (hasAnyModeManager()) {
                    Log.e(TAG, "Entered DisabledState, but has active mode managers");
                }
            }

            @Override
            public void exit() {
                log("DisabledState.exit()");
                super.exit();
            }

            @Override
            public boolean processMessageFiltered(Message msg) {
                switch (msg.what) {
                    case CMD_WIFI_TOGGLED:
                    case CMD_SCAN_ALWAYS_MODE_CHANGED:
                        if (shouldEnableSta()) {
                            startPrimaryOrScanOnlyClientModeManager();
                            transitionTo(mEnabledState);
                        }
                        break;
                    case CMD_SET_AP:
                        // note: CMD_SET_AP is handled/dropped in ECM mode - will not start here
                        if (msg.arg1 == 1) {
                            startSoftApModeManager((SoftApModeConfiguration) msg.obj);
                            transitionTo(mEnabledState);
                        }
                        break;
                    case CMD_RECOVERY_RESTART_WIFI:
                        log("Recovery triggered, already in disabled state");
                        // intentional fallthrough
                    case CMD_DEFERRED_RECOVERY_RESTART_WIFI:
                        // wait mRecoveryDelayMillis for letting driver clean reset.
                        sendMessageDelayed(CMD_RECOVERY_RESTART_WIFI_CONTINUE,
                                readWifiRecoveryDelay());
                        break;
                    case CMD_RECOVERY_RESTART_WIFI_CONTINUE:
                        if (shouldEnableSta()) {
                            startPrimaryOrScanOnlyClientModeManager();
                            transitionTo(mEnabledState);
                        }
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        class EnabledState extends BaseState {
            @Override
            public void enter() {
                log("EnabledState.enter()");
                super.enter();
                if (!hasAnyModeManager()) {
                    Log.e(TAG, "Entered EnabledState, but no active mode managers");
                }
            }

            @Override
            public void exit() {
                log("EnabledState.exit()");
                if (hasAnyModeManager()) {
                    Log.e(TAG, "Existing EnabledState, but has active mode managers");
                }
                super.exit();
            }

            @Override
            public boolean processMessageFiltered(Message msg) {
                switch (msg.what) {
                    case CMD_WIFI_TOGGLED:
                    case CMD_SCAN_ALWAYS_MODE_CHANGED:
                        if (shouldEnableSta()) {
                            if (hasAnyClientModeManager()) {
                                switchAllPrimaryOrScanOnlyClientModeManagers();
                            } else {
                                startPrimaryOrScanOnlyClientModeManager();
                            }
                        } else {
                            stopAllClientModeManagers();
                        }
                        break;
                    case CMD_ADD_LOCAL_ONLY_CLIENT_MODE_MANAGER:
                        ExternalClientModeManagerRequestListener externalRequestListener =
                                (ExternalClientModeManagerRequestListener) msg.obj;
                        if (mCanRequestMoreClientModeManagers) {
                            // Can create a concurrent client mode manager.
                            startLocalOnlyClientModeManager(externalRequestListener);
                        } else {
                            // Can't create a concurrent client mode manager, use the primary one
                            // instead.
                            externalRequestListener.onAnswer(getPrimaryClientModeManager());
                        }
                        break;
                    case CMD_REMOVE_LOCAL_ONLY_CLIENT_MODE_MANAGER:
                        stopLocalOnlyClientModeManager((ClientModeManager) msg.obj);
                        break;
                    case CMD_SET_AP:
                        // note: CMD_SET_AP is handled/dropped in ECM mode - will not start here
                        if (msg.arg1 == 1) {
                            startSoftApModeManager((SoftApModeConfiguration) msg.obj);
                        } else {
                            stopSoftApModeManagers(msg.arg2);
                        }
                        break;
                    case CMD_AIRPLANE_TOGGLED:
                        // airplane mode toggled on is handled in the default state
                        if (mSettingsStore.isAirplaneModeOn()) {
                            return NOT_HANDLED;
                        } else {
                            if (hasAnyModeManagerStopping()) {
                                // previous airplane mode toggle on is being processed, defer the
                                // message toggle off until previous processing is completed.
                                deferMessage(msg);
                            } else {
                                // when airplane mode is toggled off, but wifi is on, we can keep it
                                // on
                                log("airplane mode toggled - and airplane mode is off. return "
                                        + "handled");
                            }
                            return HANDLED;
                        }
                    case CMD_AP_STOPPED:
                    case CMD_AP_START_FAILURE:
                        if (!hasAnyModeManager()) {
                            if (shouldEnableSta()) {
                                log("SoftAp disabled, start client mode");
                                startPrimaryOrScanOnlyClientModeManager();
                            } else {
                                log("SoftAp mode disabled, return to DisabledState");
                                transitionTo(mDisabledState);
                            }
                        } else {
                            log("AP disabled, remain in EnabledState.");
                        }
                        break;
                    case CMD_STA_START_FAILURE:
                    case CMD_STA_STOPPED:
                        // Client mode stopped. Head to Disabled to wait for next command if there
                        // no active mode managers.
                        if (!hasAnyModeManager()) {
                            log("STA disabled, return to DisabledState.");
                            transitionTo(mDisabledState);
                        } else {
                            log("STA disabled, remain in EnabledState.");
                        }
                        break;
                    case CMD_RECOVERY_RESTART_WIFI:
                        final String bugTitle;
                        final String bugDetail;
                        if (msg.arg1 < SelfRecovery.REASON_STRINGS.length && msg.arg1 >= 0) {
                            bugDetail = SelfRecovery.REASON_STRINGS[msg.arg1];
                            bugTitle = "Wi-Fi BugReport: " + bugDetail;
                        } else {
                            bugDetail = "";
                            bugTitle = "Wi-Fi BugReport";
                        }
                        if (msg.arg1 != SelfRecovery.REASON_LAST_RESORT_WATCHDOG) {
                            mHandler.post(() ->
                                    mWifiDiagnostics.takeBugReport(bugTitle, bugDetail));
                        }
                        log("Recovery triggered, disable wifi");
                        deferMessage(obtainMessage(CMD_DEFERRED_RECOVERY_RESTART_WIFI));
                        shutdownWifi();
                        // onStopped will move the state machine to "DisabledState".
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }
    }
}
