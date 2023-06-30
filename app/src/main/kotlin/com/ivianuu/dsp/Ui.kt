/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.dsp

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ContentAlpha
import androidx.compose.material.LocalContentColor
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.google.accompanist.flowlayout.FlowRow
import com.ivianuu.essentials.backup.BackupAndRestoreScreen
import com.ivianuu.essentials.data.DataStore
import com.ivianuu.essentials.compose.action
import com.ivianuu.essentials.lerp
import com.ivianuu.essentials.permission.PermissionManager
import com.ivianuu.essentials.resource.Resource
import com.ivianuu.essentials.resource.collectAsResourceState
import com.ivianuu.essentials.resource.getOrElse
import com.ivianuu.essentials.resource.getOrNull
import com.ivianuu.essentials.ui.AppColors
import com.ivianuu.essentials.ui.common.HorizontalList
import com.ivianuu.essentials.ui.common.VerticalList
import com.ivianuu.essentials.ui.dialog.ListScreen
import com.ivianuu.essentials.ui.dialog.TextInputScreen
import com.ivianuu.essentials.ui.material.Scaffold
import com.ivianuu.essentials.ui.material.Slider
import com.ivianuu.essentials.ui.material.Subheader
import com.ivianuu.essentials.ui.material.TopAppBar
import com.ivianuu.essentials.ui.material.guessingContentColorFor
import com.ivianuu.essentials.ui.navigation.Model
import com.ivianuu.essentials.ui.navigation.Navigator
import com.ivianuu.essentials.ui.navigation.RootScreen
import com.ivianuu.essentials.ui.navigation.Ui
import com.ivianuu.essentials.ui.navigation.push
import com.ivianuu.essentials.ui.popup.PopupMenuButton
import com.ivianuu.essentials.ui.popup.PopupMenuItem
import com.ivianuu.essentials.ui.prefs.SliderListItem
import com.ivianuu.essentials.ui.prefs.SwitchListItem
import com.ivianuu.essentials.ui.resource.ResourceBox
import com.ivianuu.essentials.unlerp
import com.ivianuu.injekt.Provide
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
            PopupMenuItem(onSelected = model.loadConfig) { Text("Load config") }
            PopupMenuItem(onSelected = model.saveConfig) { Text("Save config") }
            PopupMenuItem(onSelected = model.deleteConfig) { Text("Delete config") }
            PopupMenuItem(onSelected = model.openBackupRestore) { Text("Backup and restore") }
          }
        }
      )
    }
  ) {
    ResourceBox(model.audioDevices) { audioDevices ->
      VerticalList {
        item {
          SwitchListItem(
            value = model.dspEnabled,
            onValueChange = model.updateDspEnabled,
            title = { Text("DSP Enabled") }
          )
        }

        item {
          FlowRow(
            modifier = Modifier
              .padding(8.dp),
            mainAxisSpacing = 8.dp,
            crossAxisSpacing = 8.dp
          ) {
            val allAudioDevices = model.audioDevices.getOrNull()
              ?.map { it.id }
              ?.toSet() ?: emptySet()

            AudioDeviceChip(
              selected = allAudioDevices.all { it in model.selectedAudioDevices },
              active = true,
              onClick = model.toggleAllAudioDevicesSelections,
              onLongClick = null
            ) {
              Text("ALL")
            }

            audioDevices
              .sortedBy { it.name.lowercase() }
              .sortedByDescending { it.id in model.connectedAudioDevices }
              .sortedByDescending { it == model.currentAudioDevice }
              .forEach { audioDevice ->
                AudioDeviceChip(
                  selected = audioDevice.id in model.selectedAudioDevices,
                  active = audioDevice == model.currentAudioDevice,
                  onClick = { model.toggleAudioDeviceSelection(audioDevice, false) },
                  onLongClick = { model.toggleAudioDeviceSelection(audioDevice, true) }
                ) {
                  Text(audioDevice.name)
                }
              }
          }
        }

        if (model.selectedAudioDevices.isEmpty()) {
          item {
            Text("Select a audio device to edit")
          }
        } else {
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
              value = unlerp(BassBoostValueRange.first, BassBoostValueRange.last, model.config.bassBoostDb),
              onValueChangeFinished = {
                model.updateBassBoost(lerp(BassBoostValueRange.first, BassBoostValueRange.last, it))
              },
              title = { Text("Bass boost") },
              valueText = { Text("${lerp(BassBoostValueRange.first, BassBoostValueRange.last, it)}db") }
            )
          }

          item {
            SliderListItem(
              value = unlerp(PostGainValueRange.first, PostGainValueRange.last, model.config.postGainDb),
              onValueChangeFinished = {
                model.updatePostGain(lerp(PostGainValueRange.first, PostGainValueRange.last, it))
              },
              title = { Text("Post gain") },
              valueText = { Text("${lerp(PostGainValueRange.first, PostGainValueRange.last, it)}db") }
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

          var internalValue by remember(value) {
            mutableStateOf(unlerp(EqValueRange.first, EqValueRange.last, value))
          }

          Layout(
            modifier = Modifier
              .height(250.dp)
              .fillMaxWidth(),
            content = {
              Slider(
                modifier = Modifier.rotate(-90f),
                value = internalValue,
                onValueChange = { internalValue = it },
                onValueChangeFinished = {
                  onBandChange(band, lerp(EqValueRange.first, EqValueRange.last, internalValue))
                }
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
            text = "${lerp(EqValueRange.first, EqValueRange.last, internalValue)}db",
            style = MaterialTheme.typography.caption
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun AudioDeviceChip(
  selected: Boolean,
  active: Boolean,
  onClick: () -> Unit,
  onLongClick: (() -> Unit)?,
  content: @Composable () -> Unit
) {
  val targetBackgroundColor = if (selected) MaterialTheme.colors.secondary
  else LocalContentColor.current.copy(alpha = ContentAlpha.disabled)
  val backgroundColor by animateColorAsState(targetBackgroundColor)
  val contentColor by animateColorAsState(guessingContentColorFor(targetBackgroundColor))
  Surface(
    modifier = Modifier
      .height(32.dp)
      .alpha(if (active) 1f else ContentAlpha.disabled),
    shape = RoundedCornerShape(50),
    color = backgroundColor,
    contentColor = contentColor
  ) {
    Box(
      modifier = Modifier
        .combinedClickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = LocalIndication.current,
          onClick = onClick,
          onLongClick = onLongClick
        )
        .padding(horizontal = 8.dp, vertical = 8.dp),
      contentAlignment = Alignment.Center
    ) {
      CompositionLocalProvider(
        LocalTextStyle provides MaterialTheme.typography.button,
        content = content
      )
    }
  }
}

data class HomeModel(
  val dspEnabled: Boolean,
  val updateDspEnabled: (Boolean) -> Unit,
  val audioDevices: Resource<List<AudioDevice>>,
  val selectedAudioDevices: Set<String>,
  val toggleAllAudioDevicesSelections: () -> Unit,
  val toggleAudioDeviceSelection: (AudioDevice, Boolean) -> Unit,
  val connectedAudioDevices: Set<String>,
  val currentAudioDevice: AudioDevice,
  val config: Config,
  val updateEqBand: (Int, Int) -> Unit,
  val updateEqFrequencies: () -> Unit,
  val resetEqFrequencies: () -> Unit,
  val updateBassBoost: (Int) -> Unit,
  val updatePostGain: (Int) -> Unit,
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
  val prefs by pref.data.collectAsState(DspPrefs())

  val config = prefs.selectedAudioDevices
    .map { prefs.configs[it] ?: Config() }
    .merge()

  suspend fun updateConfig(block: Config.() -> Config) {
    pref.updateData {
      copy(
        configs = buildMap {
          putAll(configs)
          selectedAudioDevices.forEach {
            put(it, block(prefs.configs[it] ?: Config()))
          }
        }
      )
    }
  }

  val audioDevices by audioDeviceRepository.audioDevices.collectAsResourceState()

  HomeModel(
    dspEnabled = prefs.dspEnabled,
    updateDspEnabled = action { value ->
      if (!value || permissionManager.requestPermissions(dspPermissions))
        pref.updateData { copy(dspEnabled = value) }
    },
    audioDevices = audioDevices,
    selectedAudioDevices = prefs.selectedAudioDevices,
    toggleAudioDeviceSelection = action { audioDevice, longClick ->
      pref.updateData {
        copy(
          selectedAudioDevices = if (!longClick) setOf(audioDevice.id)
          else selectedAudioDevices.toMutableSet().apply {
            if (audioDevice.id in this) remove(audioDevice.id)
            else add(audioDevice.id)
          }
        )
      }
    },
    toggleAllAudioDevicesSelections = action {
      pref.updateData {
        val allAudioDevices = audioDevices.getOrNull()?.map { it.id }?.toSet() ?: emptySet()
        copy(
          selectedAudioDevices = if (allAudioDevices.all { it in selectedAudioDevices }) emptySet()
          else allAudioDevices
        )
      }
    },
    connectedAudioDevices = remember(audioDevices) {
      combine(
        audioDevices.getOrElse { emptyList() }
          .map { audioDevice ->
            audioDeviceRepository.isAudioDeviceConnected(audioDevice.id)
              .map { audioDevice.id to it }
          }
      ) {
        it.toList()
          .filter { it.second }
          .mapTo(mutableSetOf()) { it.first }
      }
    }.collectAsState(emptySet()).value,
    currentAudioDevice = audioDeviceRepository.currentAudioDevice
      .collectAsState(AudioDevice.Phone).value,
    config = config,
    updateEqFrequencies = action {
      val frequencies = navigator.push(
        TextInputScreen(initial = config.eqDb.keys.joinToString(","))
      )
        ?.split(",")
        ?.mapNotNull { it.toIntOrNull() }
        ?.takeIf { it.size == 15 }
        ?: return@action

      updateConfig {
        copy(eqDb = frequencies.associateWith { eqDb[it] ?: 0 })
      }
    },
    resetEqFrequencies = action {
      updateConfig {
        copy(eqDb = EqBands.associateWith { eqDb[it] ?: 0 })
      }
    },
    updateEqBand = action { band, value ->
      updateConfig {
        copy(eqDb = eqDb.toMutableMap().apply { put(band, value) })
      }
    },
    updateBassBoost = action { value ->
      updateConfig { copy(bassBoostDb = value) }
    },
    updatePostGain = action { value ->
      updateConfig { copy(postGainDb = value) }
    },
    loadConfig = action {
      val newConfig = navigator.push(
        ListScreen(
          items = configRepository.configs
            .first()
            .filterNot {
              it.key == AudioDevice.Phone.id ||
                  it.key == AudioDevice.Aux.id || it.key.contains(":")
            }
            .toList()
            .sortedBy { it.first }
        ) { it.first }
      )?.second ?: return@action
      prefs.selectedAudioDevices.forEach {
        configRepository.updateConfig(it, newConfig)
      }
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
            .sortedBy { it }
        )
      ) ?: return@action
      configRepository.deleteConfig(id)
    },
    openBackupRestore = action { navigator.push(BackupAndRestoreScreen()) }
  )
}
