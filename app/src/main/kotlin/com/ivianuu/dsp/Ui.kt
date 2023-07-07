/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.dsp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
import com.ivianuu.essentials.permission.PermissionManager
import com.ivianuu.essentials.resource.Resource
import com.ivianuu.essentials.resource.collectAsResourceState
import com.ivianuu.essentials.resource.getOrElse
import com.ivianuu.essentials.ui.AppColors
import com.ivianuu.essentials.ui.common.HorizontalList
import com.ivianuu.essentials.ui.dialog.TextInputScreen
import com.ivianuu.essentials.ui.insets.localVerticalInsetsPadding
import com.ivianuu.essentials.ui.material.ListItem
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
import com.ivianuu.injekt.Provide
import kotlinx.coroutines.flow.filter
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
        title = { Text("DSP") },
        actions = {
          PopupMenuButton {
            PopupMenuItem(onSelected = model.saveCurrentConfig) { Text("Save config") }
            PopupMenuItem(onSelected = model.openBackupRestore) { Text("Backup and restore") }
          }
        }
      )
    }
  ) {
    LazyVerticalGrid(
      columns = GridCells.Fixed(2),
      contentPadding = localVerticalInsetsPadding(top = 8.dp, bottom = 8.dp),
    ) {
      item(span = { GridItemSpan(maxLineSpan) }) {
        SwitchListItem(
          value = model.dspEnabled,
          onValueChange = model.updateDspEnabled,
          title = { Text("DSP Enabled") }
        )
      }

      item(span = { GridItemSpan(maxLineSpan) }) {
        ListItem(
          title = { Text(model.currentAudioDevice.name) },
          subtitle = {
            Text(
              if (model.currentConfig.id.isUUID) "Custom"
              else model.currentConfig.id
            )
          }
        )
      }

      item(span = { GridItemSpan(maxLineSpan) }) {
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

      item(span = { GridItemSpan(maxLineSpan) }) {
        Equalizer(
          eq = model.currentConfig.eqDb.toList()
            .sortedBy { it.first }
            .toMap(),
          onBandChange = model.updateEqBand
        )
      }

      item(span = { GridItemSpan(maxLineSpan) }) {
        Subheader { Text("Other") }
      }

      item(span = { GridItemSpan(maxLineSpan) }) {
        SliderListItem(
          value = model.currentConfig.bassBoostDb,
          onValueChange = model.updateBassBoost,
          valueRange = BassBoostValueRange,
          title = { Text("Bass boost") },
          valueText = { Text("${it}db") }
        )
      }

      item(span = { GridItemSpan(maxLineSpan) }) {
        Subheader { Text("Configs") }
      }

      model.allConfigs.getOrElse { emptyList() }
        .sortedBy { it.id.lowercase() }
        .sortedByDescending { model.configUsages[it.id] ?: -1f }
        .chunked(2)
        .forEach { row ->
          row.forEachIndexed { index, config ->
            item(key = config, span = { GridItemSpan(if (row.size == 1) maxLineSpan else 1) }) {
              ListItem(
                modifier = Modifier
                  .animateItemPlacement()
                  .padding(top = 8.dp)
                  .clickable { model.updateDeviceConfig(config) },
                title = { Text(config.id) },
                trailing = {
                  PopupMenuButton {
                    PopupMenuItem(onSelected = { model.deleteConfig(config) }) {
                      Text("Delete")
                    }
                  }
                },
                contentPadding = PaddingValues(
                  start = if (index == 0 || row.size == 1) 16.dp else 8.dp,
                  end = if (index == 1 || row.size == 1) 16.dp else 8.dp,
                )
              )
            }
          }
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
  val currentConfig: DspConfig,
  val updateEqBand: (Int, Int) -> Unit,
  val updateEqFrequencies: () -> Unit,
  val resetEqFrequencies: () -> Unit,
  val updateBassBoost: (Int) -> Unit,
  val allConfigs: Resource<List<DspConfig>>,
  val updateDeviceConfig: (DspConfig) -> Unit,
  val saveCurrentConfig: () -> Unit,
  val deleteConfig: (DspConfig) -> Unit,
  val configUsages: Map<String, Float>,
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

  val currentConfig by produceState(DspConfig.Default) {
    snapshotFlow { currentAudioDevice }
      .flatMapLatest { configRepository.deviceConfig(it.id) }
      .collect { value = it }
  }

  suspend fun updateConfig(block: DspConfig.() -> DspConfig) {
    val config = currentConfig.block().copy()
      .let { if (it.id.isUUID) it else it.copy(id = randomId()) }
    configRepository.updateConfig(config)
    configRepository.updateDeviceConfig(currentAudioDevice.id, config.id)
  }

  HomeModel(
    dspEnabled = remember { pref.data.map { it.dspEnabled } }
      .collectAsState(false).value,
    updateDspEnabled = action { value ->
      if (!value || permissionManager.requestPermissions(dspPermissions))
        pref.updateData { copy(dspEnabled = value) }
    },
    currentAudioDevice = currentAudioDevice,
    currentConfig = currentConfig,
    updateEqFrequencies = action {
      val frequencies = navigator.push(
        TextInputScreen(initial = currentConfig.eqDb.keys.joinToString(","))
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
    allConfigs = remember {
      configRepository.configs
        .map { it.filterNot { it.id.isUUID } }
    }.collectAsResourceState().value,
    updateDeviceConfig = action { value ->
      configRepository.updateDeviceConfig(currentAudioDevice.id, value.id)
      configRepository.configUsed(value.id)
      if (currentConfig.id.isUUID)
        configRepository.deleteConfig(currentConfig.id)
    },
    saveCurrentConfig = action {
      val id = navigator.push(
        TextInputScreen(label = "Config id..")
      ) ?: return@action
      configRepository.updateConfig(currentConfig.copy(id = id))
      configRepository.updateDeviceConfig(currentAudioDevice.id, id)
    },
    deleteConfig = action { value -> configRepository.deleteConfig(value.id) },
    configUsages = configRepository.configUsages.collectAsState(emptyMap()).value,
    openBackupRestore = action { navigator.push(BackupAndRestoreScreen()) }
  )
}
