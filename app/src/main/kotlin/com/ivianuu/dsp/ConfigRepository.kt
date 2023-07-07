/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.dsp

import android.view.animation.AccelerateInterpolator
import com.ivianuu.essentials.AppScope
import com.ivianuu.essentials.Scoped
import com.ivianuu.essentials.coroutines.ScopedCoroutineScope
import com.ivianuu.essentials.data.DataStore
import com.ivianuu.essentials.time.Clock
import com.ivianuu.essentials.time.days
import com.ivianuu.essentials.unlerp
import com.ivianuu.injekt.Provide
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.time.Duration

@Provide @Scoped<AppScope> class ConfigRepository(
  private val clock: Clock,
  private val pref: DataStore<DspPrefs>,
  private val scope: ScopedCoroutineScope<AppScope>
) {
  val configs: Flow<List<DspConfig>> = pref.data
    .map { it.configs.values.toList() }
    .distinctUntilChanged()

  val configUsages: Flow<Map<String, Float>> = pref.data
    .map { it.configUsages.mapToUsageScores() }
    .distinctUntilChanged()

  fun config(id: String): Flow<DspConfig?> = configs
    .mapNotNull { it.singleOrNull { it.id == id } }
    .distinctUntilChanged()

  suspend fun updateConfig(config: DspConfig) {
    pref.updateData {
      copy(configs = configs + (config.id to config))
    }
  }

  suspend fun deleteConfig(id: String) {
    pref.updateData {
      copy(configs = configs.filterKeys { it != id })
    }
  }

  suspend fun updateDeviceConfig(deviceId: String, configId: String) {
    pref.updateData {
      copy(
        configsByDevice = configsByDevice + (deviceId to configId)
      )
    }
  }

  fun deviceConfig(deviceId: String): Flow<DspConfig> =
    pref.data
      .map { it.configsByDevice[deviceId] }
      .flatMapLatest {
        if (it == null) flowOf(DspConfig.Default)
        else config(it)
          .map { config ->
            config ?: DspConfig.Default
              .also {
                scope.launch {
                  updateDeviceConfig(deviceId, DspConfig.Default.id)
                }
              }
          }
      }
      .distinctUntilChanged()

  fun configUsed(id: String) {
    scope.launch {
      pref.updateData {
        val now = clock()
        copy(
          configUsages = configUsages.toMutableMap().apply {
            put(id, (this[id] ?: emptyList()) + now)
          }
        )
      }
    }
  }

  suspend fun trimUsages() {
    pref.updateData {
      copy(configUsages = configUsages.trim(14.days))
    }
  }

  private fun Map<String, List<Duration>>.trim(since: Duration): Map<String, List<Duration>> =
    mutableMapOf<String, List<Duration>>().apply {
      val now = clock()
      this@trim.keys.forEach { id ->
        val usages = this@trim[id]?.filter { it > now - since }
        if (usages?.isNotEmpty() == true) put(id, usages)
      }
    }

  fun configUsageScores(ids: List<String>): Flow<Map<String, Float>> = pref.data
    .map { it.configUsages }
    .distinctUntilChanged()
    .map {
      it
        .filter { it.key in ids }
        .mapToUsageScores()
    }
    .distinctUntilChanged()

  private val usageInterpolator = AccelerateInterpolator()

  private fun Map<String, List<Duration>>.mapToUsageScores(): Map<String, Float> {
    val now = clock()
    val firstUsage = (values
      .flatten()
      .minOrNull() ?: Duration.ZERO)

    val rawScores = this
      .mapValues { usages ->
        usages.value
          .map { usage ->
            usageInterpolator.getInterpolation(
              unlerp(
                firstUsage.inWholeMilliseconds,
                now.inWholeMilliseconds,
                usage.inWholeMilliseconds
              )
            )
          }
          .sum()
      }

    val scoreRange = (rawScores.values.minOrNull() ?: 0f)..(rawScores.values.maxOrNull() ?: 0f)

    return rawScores
      .mapValues { (_, rawScore) -> unlerp(scoreRange.start, scoreRange.endInclusive, rawScore) }
  }
}
