/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.dsp

import com.ivianuu.essentials.data.PrefModule
import com.ivianuu.injekt.Provide
import kotlinx.serialization.Serializable

@Serializable data class DspPrefs(
  val dspEnabled: Boolean = false,
  val configs: Map<String, Config> = mapOf("default" to Config()),
  val selectedAudioDevices: Set<String> = emptySet(),
  val lastAudioSessionId: Int? = null
) {
  companion object {
    @Provide val prefModule = PrefModule { DspPrefs() }
  }
}

@Serializable data class Config(
  val eqDb: Map<Int, Int> = EqBands.associateWith { 0 },
  val bassBoostDb: Int = 0,
  val postGainDb: Int = 0
)

fun List<Config>.merge(): Config = if (size == 1) single()
else Config(
  eqDb = if (map { it.eqDb }.distinct().size == 1) first().eqDb else EqBands.associateWith { 0 },
  bassBoostDb = map { it.bassBoostDb }.average().toInt(),
  postGainDb = map { it.postGainDb }.average().toInt()
)

val EqValueRange = -15..15
val BassBoostValueRange = 0..15
val PostGainValueRange = -15..15

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
