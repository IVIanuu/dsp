/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.dsp

import com.ivianuu.essentials.AppScope
import com.ivianuu.essentials.data.DataStore
import com.ivianuu.injekt.Provide
import com.ivianuu.injekt.common.Scoped
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Provide @Scoped<AppScope> class ConfigRepository(private val pref: DataStore<DspPrefs>) {
  val currentConfig: Flow<DspConfig>
    get() = pref.data
      .map { it.currentConfig }
      .distinctUntilChanged()

  val configs: Flow<Map<String, DspConfig>>
    get() = pref.data
      .map { it.dspConfigs }
      .distinctUntilChanged()

  suspend fun updateCurrentConfig(config: DspConfig) {
    pref.updateData { copy(currentConfig = config) }
  }

  suspend fun saveConfig(id: String, config: DspConfig) {
    pref.updateData { copy(dspConfigs = dspConfigs + (id to config)) }
  }

  suspend fun deleteConfig(id: String) {
    pref.updateData {
      copy(
        dspConfigs = dspConfigs
          .filterKeys { it != id }
      )
    }
  }
}
