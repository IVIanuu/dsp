package com.ivianuu.dsp

import android.Manifest
import com.ivianuu.essentials.data.*
import com.ivianuu.essentials.permission.*
import com.ivianuu.injekt.*

@Provide class DspBatteryOptimizationPermission : IgnoreBatteryOptimizationsPermission(
  "Ignore battery optimizations"
)

@Provide class DspBluetoothConnectPermission : RuntimePermission(
  permissionName = Manifest.permission.BLUETOOTH_CONNECT,
  title = "Bluetooth"
)

val dspPermissions = listOf(
  DspBatteryOptimizationPermission::class,
  DspBluetoothConnectPermission::class
)

@Provide fun dspPermissionRevokeHandler(
  pref: DataStore<DspPrefs>
) = PermissionRevokeHandler(dspPermissions) {
  pref.updateData { copy(dspEnabled = false) }
}
