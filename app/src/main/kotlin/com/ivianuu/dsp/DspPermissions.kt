package com.ivianuu.dsp

import android.Manifest
import com.ivianuu.essentials.app.AppForegroundScope
import com.ivianuu.essentials.data.DataStore
import com.ivianuu.essentials.permission.PermissionManager
import com.ivianuu.essentials.permission.PermissionRevokeHandler
import com.ivianuu.essentials.permission.ignorebatteryoptimizations.IgnoreBatteryOptimizationsPermission
import com.ivianuu.essentials.permission.runtime.RuntimePermission
import com.ivianuu.injekt.Provide
import com.ivianuu.injekt.common.typeKeyOf

@Provide class DspBatteryOptimizationPermission : IgnoreBatteryOptimizationsPermission(
  "Ignore battery optimizations"
)

@Provide class DspBluetoothConnectPermission : RuntimePermission(
  permissionName = Manifest.permission.BLUETOOTH_CONNECT,
  title = "Bluetooth"
)

val dspPermissions = listOf(
  typeKeyOf<DspBatteryOptimizationPermission>(),
  typeKeyOf<DspBluetoothConnectPermission>()
)

@Provide fun dspPermissionRevokeHandler(
  pref: DataStore<DspPrefs>
) = PermissionRevokeHandler(dspPermissions) {
  pref.updateData { copy(dspEnabled = false) }
}
