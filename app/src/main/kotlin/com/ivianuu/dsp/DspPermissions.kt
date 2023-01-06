package com.ivianuu.dsp

import com.ivianuu.essentials.data.DataStore
import com.ivianuu.essentials.permission.PermissionRevokeHandler
import com.ivianuu.essentials.permission.ignorebatteryoptimizations.IgnoreBatteryOptimizationsPermission
import com.ivianuu.injekt.Provide
import com.ivianuu.injekt.common.typeKeyOf

@Provide class DspBatteryOptimizationPermission : IgnoreBatteryOptimizationsPermission(
  "Ignore battery optimizations"
)

@Provide fun DspPermissionRevokeHandler(
  pref: DataStore<DspPrefs>
) = PermissionRevokeHandler(
  listOf(typeKeyOf<DspBatteryOptimizationPermission>())
) { pref.updateData { copy(dspEnabled = false) } }
