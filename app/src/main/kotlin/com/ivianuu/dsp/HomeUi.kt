/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.dsp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.ivianuu.essentials.data.DataStore
import com.ivianuu.essentials.lerp
import com.ivianuu.essentials.state.action
import com.ivianuu.essentials.state.bind
import com.ivianuu.essentials.ui.common.HorizontalList
import com.ivianuu.essentials.ui.common.SimpleListScreen
import com.ivianuu.essentials.ui.material.Slider
import com.ivianuu.essentials.ui.material.Subheader
import com.ivianuu.essentials.ui.material.incrementingStepPolicy
import com.ivianuu.essentials.ui.navigation.Model
import com.ivianuu.essentials.ui.navigation.ModelKeyUi
import com.ivianuu.essentials.ui.navigation.RootKey
import com.ivianuu.essentials.ui.prefs.ScaledPercentageUnitText
import com.ivianuu.essentials.ui.prefs.SliderListItem
import com.ivianuu.essentials.ui.prefs.SwitchListItem
import com.ivianuu.injekt.Provide
import kotlin.math.absoluteValue

@Provide object HomeKey : RootKey

@Provide val homeUi = ModelKeyUi<HomeKey, HomeModel> {
  SimpleListScreen("DSP") {
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

    item {
      Equalizer(eq = eq.toList()
        .sortedBy { it.first }
        .toMap(), onBandChange = updateEqBand)
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
  }
}

@Composable fun Equalizer(
  eq: Map<Float, Float>,
  onBandChange: (Float, Float) -> Unit
) {
  HorizontalList(
    contentPadding = PaddingValues(start = 16.dp, end = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    eq.forEach { (band, value) ->
      item(key = band) {
        Column(
          modifier = Modifier
            .padding(top = 24.dp, bottom = 16.dp)
            .width(36.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center
        ) {
          Text(
            text = when {
              band < 1000 -> band.toInt().toString()
              band < 10000 -> {
                if (band.toInt().toString().drop(1).all { it == '0' })
                  band.toInt().toString()[0] + "k"
                else
                  band.toInt().toString()[0] + "." + band.toInt().toString()[1] + "k"
              }
              else -> {
                if (band.toInt().toString().drop(2).all { it == '0' })
                  band.toInt().toString().take(2) + "k"
                else
                  band.toInt().toString().take(2) + "." + band.toInt().toString()[2] + "k"
              }
            },
            style = MaterialTheme.typography.caption
          )

          Spacer(Modifier.height(8.dp))

          val valueRange = -EQ_DB..EQ_DB
          val stepPolicy = incrementingStepPolicy(1f)

          var internalValue by remember(value) {
            mutableStateOf(
              lerp(
                valueRange.start,
                valueRange.endInclusive,
                value
              )
            )
          }

          Layout(
            modifier = Modifier
              .height(200.dp)
              .fillMaxWidth(),
            content = {
              Slider(
                modifier = Modifier
                  .rotate(-90f),
                value = internalValue,
                valueRange = valueRange,
                onValueChange = { internalValue = it },
                onValueChangeFinished = {
                  onBandChange(
                    band,
                    lerp(
                      0f,
                      1f,
                      calcFraction(valueRange.start, valueRange.endInclusive, internalValue)
                    )
                  )
                },
                stepPolicy = stepPolicy
              )
            }
          ) { measurables, constraints ->
            val placeable = measurables.single()
              .measure(Constraints.fixed(constraints.maxHeight, constraints.maxWidth))

            layout(constraints.maxWidth, constraints.maxHeight) {
              placeable.place(
                constraints.maxWidth / 2 - placeable.width / 2,
                constraints.maxHeight / 2 - placeable.height / 2
              )
            }
          }

          Spacer(Modifier.height(8.dp))

          val steps = stepPolicy(valueRange)
          val stepFractions = (if (steps == 0) emptyList()
          else List(steps + 2) { it.toFloat() / (steps + 1) })
          val stepValues = stepFractions
            .map {
              valueRange.start +
                  ((valueRange.endInclusive - valueRange.start) * it)
            }

          val steppedValue = stepValues
            .minByOrNull { (it - internalValue).absoluteValue }
            ?: internalValue

          Text(
            text = "${steppedValue.toInt()}db",
            style = MaterialTheme.typography.caption
          )
        }
      }
    }
  }
}

private fun calcFraction(a: Float, b: Float, pos: Float) =
  (if (b - a == 0f) 0f else (pos - a) / (b - a)).coerceIn(0f, 1f)

data class HomeModel(
  val dspEnabled: Boolean,
  val updateDspEnabled: (Boolean) -> Unit,
  val eq: Map<Float, Float>,
  val updateEqBand: (Float, Float) -> Unit,
  val bassBoost: Float,
  val updateBassBoost: (Float) -> Unit
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
    updateBassBoost = action { value -> pref.updateData { copy(bassBoost = value) } }
  )
}
