package com.ivianuu.dsp

import com.ivianuu.essentials.data.DataStore
import com.ivianuu.essentials.permission.PermissionRevokeHandler
import com.ivianuu.essentials.permission.ignorebatteryoptimizations.IgnoreBatteryOptimizationsPermission
import com.ivianuu.injekt.Provide
import com.ivianuu.injekt.common.typeKeyOf

@Provide class DspBatteryOptimizationPermission : IgnoreBatteryOptimizationsPermission(
  "Ignore battery optimizations"
)

val dspPermissions = listOf(typeKeyOf<DspBatteryOptimizationPermission>())

@Provide fun dspPermissionRevokeHandler(
  pref: DataStore<DspPrefs>
) = PermissionRevokeHandler(dspPermissions) {
  pref.updateData { copy(dspEnabled = false) }
}
