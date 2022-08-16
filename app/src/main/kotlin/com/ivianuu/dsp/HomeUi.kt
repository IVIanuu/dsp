/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.dsp

import android.content.Intent
import android.content.pm.PackageManager
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Text
import androidx.compose.ui.Modifier
import com.ivianuu.essentials.data.DataStore
import com.ivianuu.essentials.shell.Shell
import com.ivianuu.essentials.state.action
import com.ivianuu.essentials.state.bind
import com.ivianuu.essentials.ui.common.VerticalList
import com.ivianuu.essentials.ui.material.ListItem
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
import kotlinx.coroutines.delay

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
        ListItem(
          modifier = Modifier.clickable(onClick = restartSpotify),
          title = { Text("Restart Spotify") }
        )
      }
    }
  }
}

data class HomeModel(
  val dspEnabled: Boolean,
  val updateDspEnabled: (Boolean) -> Unit,
  val eq: Map<Int, Float>,
  val updateEqBand: (Int, Float) -> Unit,
  val updateBassBoost: (Float) -> Unit,
  val bassBoost: Float,
  val restartSpotify: () -> Unit
)

@Provide fun homeModel(
  activity: ComponentActivity,
  packageManager: PackageManager,
  pref: DataStore<DspPrefs>,
  shell: Shell
) = Model {
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
    restartSpotify = action {
      activity.sendOrderedBroadcast(
        mediaIntentFor(
          KeyEvent.ACTION_DOWN,
          KeyEvent.KEYCODE_MEDIA_PAUSE
        ), null
      )
      activity.sendOrderedBroadcast(
        mediaIntentFor(
          KeyEvent.ACTION_UP,
          KeyEvent.KEYCODE_MEDIA_PAUSE
        ), null
      )

      delay(2000)

      shell.run("am force-stop com.spotify.music")

      activity.startActivity(
        packageManager.getLaunchIntentForPackage("com.spotify.music")!!
      )

      delay(2000)

      activity.startActivity(activity.intent)

      activity.sendOrderedBroadcast(
        mediaIntentFor(
          KeyEvent.ACTION_DOWN,
          KeyEvent.KEYCODE_MEDIA_PLAY
        ), null
      )
      activity.sendOrderedBroadcast(
        mediaIntentFor(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY),
        null
      )
    }
  )
}

private fun mediaIntentFor(
  keyEvent: Int,
  keycode: Int
): Intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
  putExtra(
    Intent.EXTRA_KEY_EVENT,
    KeyEvent(keyEvent, keycode)
  )

  `package` = "com.spotify.music"
}
