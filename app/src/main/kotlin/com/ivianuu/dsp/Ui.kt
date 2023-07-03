/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.dsp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.ContentAlpha
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.ivianuu.essentials.backup.BackupAndRestoreScreen
import com.ivianuu.essentials.data.DataStore
import com.ivianuu.essentials.compose.action
import com.ivianuu.essentials.lerp
import com.ivianuu.essentials.permission.PermissionManager
import com.ivianuu.essentials.ui.AppColors
import com.ivianuu.essentials.ui.common.HorizontalList
import com.ivianuu.essentials.ui.common.VerticalList
import com.ivianuu.essentials.ui.dialog.ListScreen
import com.ivianuu.essentials.ui.dialog.TextInputScreen
import com.ivianuu.essentials.ui.material.Scaffold
import com.ivianuu.essentials.ui.material.Slider
import com.ivianuu.essentials.ui.material.Subheader
import com.ivianuu.essentials.ui.material.TopAppBar
import com.ivianuu.essentials.ui.navigation.Model
import com.ivianuu.essentials.ui.navigation.Navigator
import com.ivianuu.essentials.ui.navigation.RootScreen
import com.ivianuu.essentials.ui.navigation.Ui
import com.ivianuu.essentials.ui.navigation.push
import com.ivianuu.essentials.ui.popup.PopupMenuButton
import com.ivianuu.essentials.ui.popup.PopupMenuItem
import com.ivianuu.essentials.ui.prefs.SliderListItem
import com.ivianuu.essentials.ui.prefs.SwitchListItem
import com.ivianuu.essentials.unlerp
import com.ivianuu.injekt.Provide
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

@Provide val dspAppColors = AppColors(
  primary = Color(0xFFFC5C65),
  secondary = Color(0xFFF7B731)
)

@Provide class HomeScreen : RootScreen

@Provide val homeUi = Ui<HomeScreen, HomeModel> { model ->
  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text("DSP")
          Text(
            text = model.currentAudioDevice.name,
            style = MaterialTheme.typography.body2,
            color = LocalContentColor.current.copy(alpha = ContentAlpha.medium)
          )
        },
        actions = {
          PopupMenuButton {
            PopupMenuItem(onSelected = model.loadConfig) { Text("Load config") }
            PopupMenuItem(onSelected = model.saveConfig) { Text("Save config") }
            PopupMenuItem(onSelected = model.deleteConfig) { Text("Delete config") }
            PopupMenuItem(onSelected = model.openBackupRestore) { Text("Backup and restore") }
          }
        }
      )
    }
  ) {
    VerticalList {
      item {
        SwitchListItem(
          value = model.dspEnabled,
          onValueChange = model.updateDspEnabled,
          title = { Text("DSP Enabled") }
        )
      }

      item {
        val contentColor = LocalContentColor.current
        Subheader {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text("Equalizer")

            CompositionLocalProvider(LocalContentColor provides contentColor) {
              PopupMenuButton {
                PopupMenuItem(onSelected = model.updateEqFrequencies) {
                  Text("Update frequencies")
                }
                PopupMenuItem(onSelected = model.resetEqFrequencies) {
                  Text("Reset frequencies")
                }
              }
            }
          }
        }
      }

      item {
        Equalizer(
          eq = model.config.eqDb.toList()
            .sortedBy { it.first }
            .toMap(),
          onBandChange = model.updateEqBand
        )
      }

      item {
        Subheader { Text("Other") }
      }

      item {
        SliderListItem(
          value = model.config.bassBoostDb,
          onValueChange = model.updateBassBoost,
          valueRange = BassBoostValueRange,
          title = { Text("Bass boost") },
          valueText = { Text("${it}db") }
        )
      }
    }
  }
}

@Composable fun Equalizer(eq: Map<Int, Int>, onBandChange: (Int, Int) -> Unit) {
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
              band < 1000 -> band.toString()
              band < 10000 -> {
                if (band.toString().drop(1).all { it == '0' })
                  band.toString()[0] + "k"
                else
                  band.toString()[0] + "." + band.toString()[1] + "k"
              }
              else -> {
                if (band.toString().drop(2).all { it == '0' })
                  band.toString().take(2) + "k"
                else
                  band.toString().take(2) + "." + band.toString()[2] + "k"
              }
            },
            style = MaterialTheme.typography.caption
          )

          Spacer(Modifier.height(8.dp))

          var internalValue by remember(value) { mutableStateOf(value) }

          Layout(
            modifier = Modifier
              .height(250.dp)
              .fillMaxWidth(),
            content = {
              Slider(
                modifier = Modifier.rotate(-90f),
                value = value,
                onValueChange = {
                  internalValue = it
                  onBandChange(band, it)
                },
                valueRange = EqValueRange
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

          Text(
            text = "${internalValue}db",
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
  val currentAudioDevice: AudioDevice,
  val config: DspConfig,
  val updateEqBand: (Int, Int) -> Unit,
  val updateEqFrequencies: () -> Unit,
  val resetEqFrequencies: () -> Unit,
  val updateBassBoost: (Int) -> Unit,
  val loadConfig: () -> Unit,
  val saveConfig: () -> Unit,
  val deleteConfig: () -> Unit,
  val openBackupRestore: () -> Unit
)

@Provide fun homeModel(
  audioDeviceRepository: AudioDeviceRepository,
  configRepository: ConfigRepository,
  navigator: Navigator,
  permissionManager: PermissionManager,
  pref: DataStore<DspPrefs>
) = Model {
  val currentAudioDevice by audioDeviceRepository.currentAudioDevice
    .collectAsState(AudioDevice.Phone)

  val config by produceState(DspConfig()) {
    snapshotFlow { currentAudioDevice }
      .flatMapLatest { configRepository.config(it.id) }
      .collect { value = it ?: DspConfig() }
  }

  suspend fun updateConfig(block: DspConfig.() -> DspConfig) {
    configRepository.updateConfig(currentAudioDevice.id, config.block())
  }

  HomeModel(
    dspEnabled = remember { pref.data.map { it.dspEnabled } }
      .collectAsState(false).value,
    updateDspEnabled = action { value ->
      if (!value || permissionManager.requestPermissions(dspPermissions))
        pref.updateData { copy(dspEnabled = value) }
    },
    currentAudioDevice = currentAudioDevice,
    config = config,
    updateEqFrequencies = action {
      val frequencies = navigator.push(
        TextInputScreen(initial = config.eqDb.keys.joinToString(","))
      )
        ?.split(",")
        ?.mapNotNull { it.toIntOrNull() }
        ?.takeIf { it.size == 15 }
        ?: return@action

      updateConfig { copy(eqDb = frequencies.associateWith { eqDb[it] ?: 0 }) }
    },
    resetEqFrequencies = action {
      updateConfig { copy(eqDb = EqBands.associateWith { eqDb[it] ?: 0 }) }
    },
    updateEqBand = action { band, value ->
      updateConfig {
        copy(eqDb = eqDb.toMutableMap().apply { put(band, value) })
      }
    },
    updateBassBoost = action { value ->
      updateConfig { copy(bassBoostDb = value) }
    },
    loadConfig = action {
      val newConfig = navigator.push(
        ListScreen(
          items = configRepository.configs
            .first()
            .filterNot {
              it.key == AudioDevice.Phone.id || it.key.contains(":")
            }
            .toList()
            .sortedBy { it.first }
        ) { it.first }
      )?.second ?: return@action
      updateConfig { newConfig }
    },
    saveConfig = action {
      val id = navigator.push(
        TextInputScreen(label = "Config id..")
      ) ?: return@action
      configRepository.updateConfig(id, config)
    },
    deleteConfig = action {
      val id = navigator.push(
        ListScreen(
          items = configRepository.configs
            .first()
            .keys
            .filterNot { it == AudioDevice.Phone.id || it.contains(":") }
            .sortedBy { it }
        )
      ) ?: return@action
      configRepository.deleteConfig(id)
    },
    openBackupRestore = action { navigator.push(BackupAndRestoreScreen()) }
  )
}
