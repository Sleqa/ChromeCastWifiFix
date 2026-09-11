package com.sleqa.wififix.admin

import android.app.admin.DeviceAdminReceiver

/**
 * Exists only so the app can be provisioned as device owner:
 *
 *   adb shell dpm set-device-owner com.sleqa.wififix/.admin.WifiFixAdminReceiver
 *
 * It enforces no policies. See README for the preconditions and the
 * factory-reset-to-undo warning.
 */
class WifiFixAdminReceiver : DeviceAdminReceiver()
