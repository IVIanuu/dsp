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

const val EQ_DB = 10f
const val BASS_BOOST_DB = 10f
const val POST_GAIN_DB = 40f

val EqBands = listOf(
  60f,
  230f,
  910f,
  3600f,
  140000f
)
