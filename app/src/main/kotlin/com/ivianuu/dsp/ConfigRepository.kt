/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.dsp

import android.annotation.*
import android.bluetooth.*
import android.view.animation.*
import com.ivianuu.essentials.*
import com.ivianuu.essentials.coroutines.*
import com.ivianuu.essentials.data.*
import com.ivianuu.essentials.time.*
import com.ivianuu.injekt.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.*
import kotlin.time.Duration.Companion.days

@SuppressLint("MissingPermission")
@Provide @Scoped<AppScope> class ConfigRepository(
  private val bluetoothManager: @SystemService BluetoothManager,
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

  init {
    scope.launch {
      pref.updateData {
        val newConfigsByDevice = if (!bluetoothManager.adapter.isEnabled) configsByDevice
        else configsByDevice
          .filter { it.key in bluetoothManager.adapter.bondedDevices.map { it.address } }
        copy(
          configUsages = configUsages.trim(28.days),
          configsByDevice = newConfigsByDevice,
          configs = configs
            .filter { !it.key.isUUID || it.key in newConfigsByDevice.values }
        )
      }
    }
  }

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
        val now = clock.now()
        copy(
          configUsages = configUsages.toMutableMap().apply {
            put(id, (this[id] ?: emptyList()) + now)
          }
        )
      }
    }
  }

  private fun Map<String, List<Duration>>.trim(since: Duration): Map<String, List<Duration>> =
    mutableMapOf<String, List<Duration>>().apply {
      val now = clock.now()
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

  private val usageInterpolator = AccelerateInterpolator(2f)

  private fun Map<String, List<Duration>>.mapToUsageScores(): Map<String, Float> {
    val now = clock.now()
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
