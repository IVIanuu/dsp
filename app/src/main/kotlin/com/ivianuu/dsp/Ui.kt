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
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
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
import com.ivianuu.essentials.unlerp
import com.ivianuu.injekt.Provide
import kotlinx.coroutines.flow.first

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
          eq = model.currentConfig.eqDb.toList()
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
          value = unlerp(BassBoostValueRange.first, BassBoostValueRange.last, model.currentConfig.bassBoostDb),
          onValueChangeFinished = {
            model.updateBassBoost(lerp(BassBoostValueRange.first, BassBoostValueRange.last, it))
          },
          title = { Text("Bass boost") },
          valueText = { Text("${lerp(BassBoostValueRange.first, BassBoostValueRange.last, it)}db") }
        )
      }

      item {
        SliderListItem(
          value = unlerp(PostGainValueRange.first, PostGainValueRange.last, model.currentConfig.postGainDb),
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

data class HomeModel(
  val dspEnabled: Boolean,
  val updateDspEnabled: (Boolean) -> Unit,
  val currentConfig: Config,
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
  configRepository: ConfigRepository,
  navigator: Navigator,
  permissionManager: PermissionManager,
  pref: DataStore<DspPrefs>
) = Model {
  val prefs by pref.data.collectAsState(DspPrefs())

  val currentConfig by configRepository.currentConfig.collectAsState(Config())

  HomeModel(
    dspEnabled = prefs.dspEnabled,
    updateDspEnabled = action { value ->
      if (!value || permissionManager.requestPermissions(dspPermissions))
        pref.updateData { copy(dspEnabled = value) }
    },
    currentConfig = currentConfig,
    updateEqFrequencies = action {
      val frequencies = navigator.push(
        TextInputScreen(
          initial = currentConfig.eqDb.keys.joinToString(",")
        )
      )
        ?.split(",")
        ?.mapNotNull { it.toIntOrNull() }
        ?.takeIf { it.size == 15 }
        ?: return@action

      configRepository.updateCurrentConfig(
        currentConfig.copy(
          eqDb = frequencies.associateWith {
            currentConfig.eqDb[it] ?: 0
          }
        )
      )
    },
    resetEqFrequencies = action {
      configRepository.updateCurrentConfig(
        currentConfig.copy(
          eqDb = EqBands.associateWith {
            currentConfig.eqDb[it] ?: 0
          }
        )
      )
    },
    updateEqBand = action { band, value ->
      configRepository.updateCurrentConfig(
        currentConfig.copy(
          eqDb = currentConfig.eqDb.toMutableMap().apply {
            put(band, value)
          }
        )
      )
    },
    updateBassBoost = action { value ->
      configRepository.updateCurrentConfig(currentConfig.copy(bassBoostDb = value))
    },
    updatePostGain = action { value ->
      configRepository.updateCurrentConfig(currentConfig.copy(postGainDb = value))
    },
    loadConfig = action {
      val config = navigator.push(
        ListScreen(
          items = configRepository.configs
            .first()
            .toList()
            .sortedBy { it.first }
        ) { it.first }
      )?.second ?: return@action
      configRepository.updateCurrentConfig(config)
    },
    saveConfig = action {
      val id = navigator.push(
        TextInputScreen(label = "Config id..")
      ) ?: return@action
      configRepository.saveConfig(id, currentConfig)
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
