/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.dsp

import com.ivianuu.essentials.android.prefs.DataStoreModule
import com.ivianuu.injekt.Provide
import kotlinx.serialization.Serializable

@Serializable data class DspPrefs(
  val dspEnabled: Boolean = false,
  val eq: Map<Float, Float> = EqBands.associateWith { 0.5f },
  val bassBoost: Float = 0.0f
) {
  companion object {
    @Provide val prefModule = DataStoreModule("dsp_prefs") { DspPrefs() }
  }
}

const val EQ_DB = 15f
const val BASS_BOOST_DB = 10f

val EqBands = listOf(
  60f,
  230f,
  910f,
  3600f,
  140000f
)
