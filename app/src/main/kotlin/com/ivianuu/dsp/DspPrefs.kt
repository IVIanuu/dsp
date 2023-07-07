/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.dsp

import com.ivianuu.essentials.data.PrefModule
import com.ivianuu.injekt.Provide
import kotlinx.serialization.Serializable
import kotlin.time.Duration

@Serializable data class DspPrefs(
  val dspEnabled: Boolean = false,
  val configs: Map<String, DspConfig> = mapOf("default" to DspConfig.Default),
  val configsByDevice: Map<String, String> = emptyMap(),
  val configUsages: Map<String, List<Duration>> = emptyMap(),
  val lastAudioSessionId: Int? = null,
) {
  companion object {
    @Provide val dataStoreModule = PrefModule { DspPrefs() }
  }
}

@Serializable data class DspConfig(
  val id: String,
  val eqDb: Map<Int, Int> = EqBands.associateWith { 0 },
  val bassBoostDb: Int = 0
) {
  companion object {
    val Default = DspConfig("default")
  }
}

val EqValueRange = -15..15
val BassBoostValueRange = 0..15

val EqBands = listOf(
  40,
  60,
  80,
  100,
  160,
  250,
  400,
  630,
  1000,
  1600,
  2500,
  4000,
  6300,
  10000,
  16000
)
