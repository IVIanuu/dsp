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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.ivianuu.essentials.backup.BackupAndRestoreKey
import com.ivianuu.essentials.data.DataStore
import com.ivianuu.essentials.lerp
import com.ivianuu.essentials.compose.action
import com.ivianuu.essentials.compose.bind
import com.ivianuu.essentials.permission.PermissionManager
import com.ivianuu.essentials.ui.AppColors
import com.ivianuu.essentials.ui.common.HorizontalList
import com.ivianuu.essentials.ui.common.VerticalList
import com.ivianuu.essentials.ui.dialog.ListKey
import com.ivianuu.essentials.ui.dialog.TextInputKey
import com.ivianuu.essentials.ui.material.Scaffold
import com.ivianuu.essentials.ui.material.Slider
import com.ivianuu.essentials.ui.material.Subheader
import com.ivianuu.essentials.ui.material.TopAppBar
import com.ivianuu.essentials.ui.material.incrementingStepPolicy
import com.ivianuu.essentials.ui.navigation.KeyUiContext
import com.ivianuu.essentials.ui.navigation.Model
import com.ivianuu.essentials.ui.navigation.ModelKeyUi
import com.ivianuu.essentials.ui.navigation.RootKey
import com.ivianuu.essentials.ui.navigation.push
import com.ivianuu.essentials.ui.popup.PopupMenuButton
import com.ivianuu.essentials.ui.popup.PopupMenuItem
import com.ivianuu.essentials.ui.prefs.SliderListItem
import com.ivianuu.essentials.ui.prefs.SwitchListItem
import com.ivianuu.essentials.unlerp
import com.ivianuu.injekt.Provide
import kotlinx.coroutines.flow.first
import kotlin.math.absoluteValue

@Provide val dspAppColors = AppColors(
  primary = Color(0xFFFC5C65),
  secondary = Color(0xFFF7B731)
)

@Provide object HomeKey : RootKey

@Provide val homeUi = ModelKeyUi<HomeKey, HomeModel> {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("DSP") },
        actions = {
          PopupMenuButton {
            PopupMenuItem(onSelected = loadConfig) { Text("Load config") }
            PopupMenuItem(onSelected = saveConfig) { Text("Save config") }
            PopupMenuItem(onSelected = deleteConfig) { Text("Delete config") }
            PopupMenuItem(onSelected = openBackupRestore) { Text("Backup and restore") }
          }
        }
      )
    }
  ) {
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

      item {
        Equalizer(eq = currentConfig.eq.toList()
          .sortedBy { it.first }
          .toMap(), onBandChange = updateEqBand)
      }

      item {
        Subheader { Text("Other") }
      }

      item {
        val valueRange = 0f..BASS_BOOST_DB
        SliderListItem(
          value = lerp(valueRange.start, valueRange.endInclusive, currentConfig.bassBoost),
          onValueChangeFinished = {
            updateBassBoost(unlerp(valueRange.start, valueRange.endInclusive, it))
          },
          valueRange = valueRange,
          title = { Text("Bass boost") },
          stepPolicy = incrementingStepPolicy(1f),
          valueText = { Text("${it.toInt()}db") }
        )
      }
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
              .height(250.dp)
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
                    unlerp(valueRange.start, valueRange.endInclusive, internalValue)
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

data class HomeModel(
  val dspEnabled: Boolean,
  val updateDspEnabled: (Boolean) -> Unit,
  val currentConfig: Config,
  val updateEqBand: (Float, Float) -> Unit,
  val updateBassBoost: (Float) -> Unit,
  val loadConfig: () -> Unit,
  val saveConfig: () -> Unit,
  val deleteConfig: () -> Unit,
  val openBackupRestore: () -> Unit
)

@Provide fun homeModel(
  configRepository: ConfigRepository,
  permissionManager: PermissionManager,
  pref: DataStore<DspPrefs>,
  ctx: KeyUiContext<HomeKey>
) = Model {
  val prefs = pref.data.bind(DspPrefs())

  val currentConfig = configRepository.currentConfig.bind(Config())

  HomeModel(
    dspEnabled = prefs.dspEnabled,
    updateDspEnabled = action { value ->
      if (!value || permissionManager.requestPermissions(dspPermissions))
        pref.updateData { copy(dspEnabled = value) }
    },
    currentConfig = currentConfig,
    updateEqBand = action { band, value ->
      configRepository.updateCurrentConfig(
        currentConfig.copy(
          eq = currentConfig.eq.toMutableMap().apply {
            put(band, value)
          }
        )
      )
    },
    updateBassBoost = action { value ->
      configRepository.updateCurrentConfig(currentConfig.copy(bassBoost = value))
    },
    loadConfig = action {
      val config = ctx.navigator.push(
        ListKey(
          items = configRepository.configs
            .first()
            .toList()
            .sortedBy { it.first }
        ) { it.first }
      )?.second ?: return@action
      configRepository.updateCurrentConfig(config)
    },
    saveConfig = action {
      val id = ctx.navigator.push(
        TextInputKey(label = "Config id..")
      ) ?: return@action
      configRepository.saveConfig(id, currentConfig)
    },
    deleteConfig = action {
      val id = ctx.navigator.push(
        ListKey(
          items = configRepository.configs
            .first()
            .keys
            .sortedBy { it }
        )
      ) ?: return@action
      configRepository.deleteConfig(id)
    },
    openBackupRestore = action { ctx.navigator.push(BackupAndRestoreKey()) }
  )
}
