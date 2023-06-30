/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.dsp

import com.ivianuu.essentials.AppScope
import com.ivianuu.essentials.Scoped
import com.ivianuu.essentials.data.DataStore
import com.ivianuu.injekt.Provide
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Provide @Scoped<AppScope> class ConfigRepository(private val pref: DataStore<DspPrefs>) {
  val configs: Flow<Map<String, Config>> = pref.data
    .map { it.configs }
    .distinctUntilChanged()

  fun config(id: String): Flow<Config?> = configs
    .map { it[id] }
    .distinctUntilChanged()

  suspend fun updateConfig(id: String, config: Config) {
    pref.updateData {
      copy(configs = configs + (id to config))
    }
  }

  suspend fun deleteConfig(id: String) {
    pref.updateData {
      copy(configs = configs.filterKeys { it != id })
    }
  }
}
