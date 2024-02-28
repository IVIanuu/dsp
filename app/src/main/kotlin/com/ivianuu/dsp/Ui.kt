/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

@file:OptIn(ExperimentalFoundationApi::class)

package com.ivianuu.dsp

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.unit.*
import com.ivianuu.essentials.backup.*
import com.ivianuu.essentials.compose.*
import com.ivianuu.essentials.data.*
import com.ivianuu.essentials.permission.*
import com.ivianuu.essentials.resource.*
import com.ivianuu.essentials.ui.app.*
import com.ivianuu.essentials.ui.common.*
import com.ivianuu.essentials.ui.dialog.*
import com.ivianuu.essentials.ui.material.*
import com.ivianuu.essentials.ui.navigation.*
import com.ivianuu.essentials.ui.prefs.*
import com.ivianuu.injekt.*
import kotlinx.coroutines.flow.*

@Provide val dspAppColors = AppColors(
  primary = Color(0xFFFC5C65),
  secondary = Color(0xFFF7B731)
)

@Provide class HomeScreen : RootScreen {
  @Provide companion object {
    @Provide fun ui(
      audioDeviceRepository: AudioDeviceRepository,
      configRepository: ConfigRepository,
      navigator: Navigator,
      permissionManager: PermissionManager,
      pref: DataStore<DspPrefs>
    ) = Ui<HomeScreen> {
      val currentAudioDevice = audioDeviceRepository.currentAudioDevice.collect(AudioDevice.Phone)

      val currentConfig = configRepository.deviceConfig(currentAudioDevice.id)
        .collect(DspConfig.Default, currentAudioDevice)

      suspend fun updateConfig(block: DspConfig.() -> DspConfig) {
        val config = currentConfig.block().copy()
          .let { if (it.id.isUUID) it else it.copy(id = randomId()) }
        configRepository.updateConfig(config)
        configRepository.updateDeviceConfig(currentAudioDevice.id, config.id)
      }

      val allConfigs = configRepository.configs
        .map { it.filterNot { it.id.isUUID } }
        .collectResource()
      val configUsages = configRepository.configUsages.collect(emptyMap())

      ScreenScaffold(
        topBar = {
          AppBar(
            title = { Text("DSP") },
            actions = {
              DropdownMenuButton {
                DropdownMenuItem(onClick = scopedAction {
                  val id = navigator.push(
                    TextInputScreen(label = "Config id..")
                  ) ?: return@scopedAction
                  configRepository.updateConfig(currentConfig.copy(id = id))
                  configRepository.updateDeviceConfig(currentAudioDevice.id, id)
                }) { Text("Save config") }
                DropdownMenuItem(onClick = scopedAction {
                  navigator.push(BackupAndRestoreScreen())
                }) { Text("Backup and restore") }
              }
            }
          )
        }
      ) {
        LazyVerticalGrid(columns = GridCells.Fixed(2)) {
          item(span = { GridItemSpan(maxLineSpan) }) {
            SwitchListItem(
              value = pref.data.map { it.dspEnabled }.collect(false),
              onValueChange = scopedAction { value ->
                if (!value || permissionManager.requestPermissions(dspPermissions))
                  pref.updateData { copy(dspEnabled = value) }
              },
              title = { Text("DSP Enabled") }
            )
          }

          item(span = { GridItemSpan(maxLineSpan) }) {
            ListItem(
              title = { Text(currentAudioDevice.name) },
              subtitle = {
                Text(
                  if (currentConfig.id.isUUID) "Custom"
                  else currentConfig.id
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
                  DropdownMenuButton {
                    DropdownMenuItem(onClick = scopedAction {
                      val frequencies = navigator.push(
                        TextInputScreen(initial = currentConfig.eqDb.keys.joinToString(","))
                      )
                        ?.split(",")
                        ?.mapNotNull { it.toIntOrNull() }
                        ?.takeIf { it.size == 15 }
                        ?: return@scopedAction

                      updateConfig { copy(eqDb = frequencies.associateWith { eqDb[it] ?: 0 }) }
                    }) {
                      Text("Update frequencies")
                    }
                    DropdownMenuItem(onClick = scopedAction {
                      updateConfig { copy(eqDb = DefaultEqBands.associateWith { eqDb[it] ?: 0 }) }
                    }) {
                      Text("Reset frequencies")
                    }
                  }
                }
              }
            }
          }

          item(span = { GridItemSpan(maxLineSpan) }) {
            Equalizer(
              eq = currentConfig.eqDb.toList()
                .sortedBy { it.first }
                .toMap(),
              onBandChange = scopedAction { band, value ->
                updateConfig {
                  copy(eqDb = eqDb.toMutableMap().apply { put(band, value) })
                }
              }
            )
          }

          item(span = { GridItemSpan(maxLineSpan) }) {
            Subheader { Text("Other") }
          }

          item(span = { GridItemSpan(maxLineSpan) }) {
            SliderListItem(
              value = currentConfig.bassBoostDb,
              onValueChange = scopedAction { value ->
                updateConfig { copy(bassBoostDb = value) }
              },
              valueRange = BassBoostValueRange,
              title = { Text("Bass boost") },
              valueText = { Text("${it}db") }
            )
          }

          item(span = { GridItemSpan(maxLineSpan) }) {
            Subheader { Text("Configs") }
          }

          allConfigs.getOrElse { emptyList() }
            .sortedBy { it.id.lowercase() }
            .sortedByDescending { configUsages[it.id] ?: -1f }
            .chunked(2)
            .forEach { row ->
              row.forEachIndexed { index, config ->
                item(key = config, span = { GridItemSpan(if (row.size == 1) maxLineSpan else 1) }) {
                  ListItem(
                    modifier = Modifier
                      .animateItemPlacement()
                      .padding(top = 8.dp)
                      .clickable(onClick = scopedAction {
                        configRepository.updateDeviceConfig(currentAudioDevice.id, config.id)
                        configRepository.configUsed(config.id)
                        if (currentConfig.id.isUUID)
                          configRepository.deleteConfig(currentConfig.id)
                      }),
                    title = { Text(config.id) },
                    trailing = {
                      DropdownMenuButton {
                        DropdownMenuItem(
                          onClick = scopedAction { configRepository.deleteConfig(config.id) }
                        ) {
                          Text("Delete")
                        }
                      }
                    },
                    leadingPadding = PaddingValues(
                      start = if (index == 0 || row.size == 1) 16.dp else 8.dp
                    ),
                    trailingPadding = PaddingValues(
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

              var internalValue by remember(value) { mutableIntStateOf(value) }

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
  }
}
