/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.dsp

import com.ivianuu.essentials.android.prefs.DataStoreModule
import com.ivianuu.injekt.Provide
import kotlinx.serialization.Serializable

@Serializable data class DspPrefs(
  val dspEnabled: Boolean = false,
  val bassBoost: Float = 0.0f,
  val eq: Map<Int, Float> = EqBands.associateWith { 0.5f }
) {
  companion object {
    @Provide val prefModule = DataStoreModule("dsp_prefs") { DspPrefs() }
  }
}

val EqBands = listOf(
  25,
  40,
  60,
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
