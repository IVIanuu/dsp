/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.dsp

import com.ivianuu.essentials.android.prefs.PrefModule
import com.ivianuu.injekt.Provide
import kotlinx.serialization.Serializable

@Serializable data class DspPrefs(
  val dspEnabled: Boolean = false,
  val currentConfig: Config = Config(),
  val configs: Map<String, Config> = mapOf("default" to Config())
) {
  companion object {
    @Provide val prefModule = PrefModule { DspPrefs() }
  }
}

@Serializable data class Config(
  val eq: Map<Float, Float> = EqBands.associateWith { 0.5f },
  val bassBoost: Float = 0.0f,
  val postGain: Float = 0.0f
)

const val EQ_DB = 15f
const val BASS_BOOST_DB = 15f
const val POST_GAIN_DB = 100f

val EqBands = listOf(
  25f,
  40f,
  60f,
  100f,
  160f,
  250f,
  400f,
  630f,
  1000f,
  1600f,
  2500f,
  4000f,
  6300f,
  10000f,
  16000f
)
