/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.dsp

import androidx.compose.foundation.lazy.items
import androidx.compose.material.Text
import com.ivianuu.essentials.data.DataStore
import com.ivianuu.essentials.state.action
import com.ivianuu.essentials.state.bind
import com.ivianuu.essentials.ui.common.VerticalList
import com.ivianuu.essentials.ui.material.Scaffold
import com.ivianuu.essentials.ui.material.Subheader
import com.ivianuu.essentials.ui.material.TopAppBar
import com.ivianuu.essentials.ui.material.incrementingStepPolicy
import com.ivianuu.essentials.ui.navigation.Model
import com.ivianuu.essentials.ui.navigation.ModelKeyUi
import com.ivianuu.essentials.ui.navigation.RootKey
import com.ivianuu.essentials.ui.prefs.ScaledPercentageUnitText
import com.ivianuu.essentials.ui.prefs.SliderListItem
import com.ivianuu.essentials.ui.prefs.SwitchListItem
import com.ivianuu.injekt.Provide

@Provide object HomeKey : RootKey

@Provide val homeUi = ModelKeyUi<HomeKey, HomeModel> {
  Scaffold(topBar = { TopAppBar(title = { Text("DSP") }) }) {
    VerticalList {
      item {
        SwitchListItem(
          value = dspEnabled,
          onValueChange = updateDspEnabled,
          title = { Text("DSP Enabled") }
        )
      }

      item {
        Subheader { Text("Equalizer") }
      }

      items(
        eq.toList()
          .sortedBy { it.first }
      ) { (band, value) ->
        SliderListItem(
          value = value,
          onValueChange = { updateEqBand(band, it) },
          title = { Text(band.toString()) },
          stepPolicy = incrementingStepPolicy(0.05f),
          valueText = { ScaledPercentageUnitText(it) }
        )
      }

      item {
        Subheader { Text("Other") }
      }

      item {
        SliderListItem(
          value = bassBoost,
          onValueChange = updateBassBoost,
          title = { Text("Bass boost") },
          stepPolicy = incrementingStepPolicy(0.05f),
          valueText = { ScaledPercentageUnitText(it) }
        )
      }

      item {
        SwitchListItem(
          value = curving,
          onValueChange = updateCurving,
          title = { Text("Curving") }
        )
      }

      item {
        SwitchListItem(
          value = customEqBands,
          onValueChange = updateCustomEqBands,
          title = { Text("Custom eq bands") }
        )
      }
    }
  }
}

data class HomeModel(
  val dspEnabled: Boolean,
  val updateDspEnabled: (Boolean) -> Unit,
  val eq: Map<Float, Float>,
  val updateEqBand: (Float, Float) -> Unit,
  val updateBassBoost: (Float) -> Unit,
  val bassBoost: Float,
  val curving: Boolean,
  val updateCurving: (Boolean) -> Unit,
  val customEqBands: Boolean,
  val updateCustomEqBands: (Boolean) -> Unit
)

@Provide fun homeModel(pref: DataStore<DspPrefs>) = Model {
  val prefs = pref.data.bind(DspPrefs())

  HomeModel(
    dspEnabled = prefs.dspEnabled,
    updateDspEnabled = action { value -> pref.updateData { copy(dspEnabled = value) } },
    eq = prefs.eq,
    updateEqBand = action { band, value ->
      pref.updateData {
        copy(eq = eq.toMutableMap().apply {
          put(band, value)
        })
      }
    },
    bassBoost = prefs.bassBoost,
    updateBassBoost = action { value -> pref.updateData { copy(bassBoost = value) } },
    curving = prefs.curving,
    updateCurving = action { value -> pref.updateData { copy(curving = value) } },
    customEqBands = prefs.customEqBands,
    updateCustomEqBands = action { value -> pref.updateData { copy(customEqBands = value) } }
  )
}
