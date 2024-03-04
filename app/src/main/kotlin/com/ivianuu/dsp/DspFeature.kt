/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.dsp

import android.media.audiofx.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import co.touchlab.kermit.*
import com.ivianuu.essentials.*
import com.ivianuu.essentials.app.*
import com.ivianuu.essentials.compose.*
import com.ivianuu.essentials.data.*
import com.ivianuu.essentials.foreground.*
import com.ivianuu.essentials.util.*
import com.ivianuu.injekt.*
import kotlinx.coroutines.flow.*
import java.nio.*
import java.util.*

@Provide class DspFeature(
  private val audioDeviceRepository: AudioDeviceRepository,
  private val broadcastManager: BroadcastManager,
  private val configRepository: ConfigRepository,
  private val foregroundManager: ForegroundManager,
  private val logger: Logger,
  private val pref: DataStore<DspPrefs>
) : ScopeComposition<AppScope> {
  @Composable override fun Content() {
    val enabled = pref.data.state(null)?.dspEnabled == true

    if (enabled)
      foregroundManager.Foreground("dsp")

    val config = audioDeviceRepository.currentAudioDevice
      .onEach { logger.d { "current device changed $it" } }
      .flatMapLatest { configRepository.deviceConfig(it.id) }
      .state(null) ?: return

    var audioSessionIds by remember { mutableStateOf(listOf<Int>()) }
    LaunchedEffect(true) {
      broadcastManager.broadcasts(
        AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION,
        AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION
      )
        .map { it.action to it.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1) }
        .onStart {
          pref.data.first().lastAudioSessionId
            ?.let { emit(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION to it) }
        }
        .collect { (event, sessionId) ->
          audioSessionIds = when (event) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
              pref.updateData { copy(lastAudioSessionId = sessionId) }
              audioSessionIds + sessionId
            }
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> audioSessionIds - sessionId
            else -> audioSessionIds
          }
        }
    }

    LaunchedEffect(audioSessionIds) {
      logger.d { "audio sessions changed $audioSessionIds" }
    }

    audioSessionIds.forEach { audioSessionId ->
      key(audioSessionId) {
        AudioSession(audioSessionId, enabled, config) {
          logger.d { "init failure $audioSessionId" }
          audioSessionIds = audioSessionIds - audioSessionId
        }
      }
    }
  }

  @Composable fun AudioSession(
    audioSessionId: Int,
    enabled: Boolean,
    config: DspConfig,
    onInitFailure: () -> Unit
  ) {
    val jamesDsp = remember {
      var jamesDsp: AudioEffect? = null
      var attempt = 0

      while (jamesDsp == null && attempt < 5) {
        catch {
          AudioEffect::class.java
            .getConstructor(UUID::class.java, UUID::class.java, Integer.TYPE, Integer.TYPE).newInstance(
              UUID.fromString("f98765f4-c321-5de6-9a45-123459495ab2"),
              UUID.fromString("f27317f4-c984-4de6-9a90-545759495bf2"),
              0,
              audioSessionId
            )
        }
          .printErrors()
          .onRight { jamesDsp = it }
        attempt++
      }

      if (jamesDsp == null)
        onInitFailure()
      else
        logger.d { "$audioSessionId -> start" }

      jamesDsp
    }
      ?.also { jamesDsp ->
        DisposableEffect(true) {
          onDispose {
            logger.d { "$audioSessionId -> stop" }
            catch { jamesDsp.release() }
              .printErrors()
          }
        }
      }
      ?: return

    LaunchedEffect(jamesDsp, enabled) {
      logger.d { "$audioSessionId update enabled $enabled" }
      jamesDsp.enabled = enabled
    }

    LaunchedEffect(jamesDsp) {
      // eq switch
      jamesDsp.setParameterShort(1202, 1)
    }

    val eqAsList = config.eqDb.toList()
    var previousEq by remember { mutableStateOf(emptyMap<Int, Int>()) }

    val animationSpec = tween<Int>(
      if (config.eqDb.count { previousEq[it.key] != it.value } > 1) 1000
      else 0
    )

    val animatedEq = (0 until 15).associate { index ->
      key(index) {
        animateIntAsState(eqAsList[index].first, animationSpec).value to
            animateIntAsState(eqAsList[index].second, animationSpec).value
      }
    }

    // eq levels
    LaunchedEffect(jamesDsp, animatedEq) {
      previousEq = config.eqDb

      val sortedEq = animatedEq
        .toList()
        .sortedBy { it.first }

      val eqLevels = (sortedEq.map { it.first.toFloat() } +
          sortedEq.map { (_, value) -> value.toFloat() }).toFloatArray()

      logger.d { "$audioSessionId update eq ${eqLevels.contentToString()}" }
      jamesDsp.setParameterFloatArray(116, floatArrayOf(3f,  -1f) + eqLevels)
    }

    // bass boost switch
    LaunchedEffect(jamesDsp) { jamesDsp.setParameterShort(1201, 1) }

    // bass boost gain
    LaunchedEffect(config.bassBoostDb) {
      logger.d { "$audioSessionId update bass boost ${config.bassBoostDb}" }
      jamesDsp.setParameterShort(112, config.bassBoostDb.toShort())
    }
  }

  private fun AudioEffect.setParameterShort(parameter: Int, value: Short) {
    val arguments = byteArrayOf(
      parameter.toByte(), (parameter shr 8).toByte(),
      (parameter shr 16).toByte(), (parameter shr 24).toByte()
    )
    val result = byteArrayOf(
      value.toByte(), (value.toInt() shr 8).toByte()
    )
    val setParameter = AudioEffect::class.java.getMethod(
      "setParameter",
      ByteArray::class.java,
      ByteArray::class.java
    )
    setParameter.invoke(this, arguments, result)
  }

  private fun AudioEffect.setParameterFloatArray(parameter: Int, value: FloatArray) {
    val arguments = byteArrayOf(
      parameter.toByte(), (parameter shr 8).toByte(),
      (parameter shr 16).toByte(), (parameter shr 24).toByte()
    )
    val result = ByteArray(value.size * 4)
    val byteDataBuffer = ByteBuffer.wrap(result)
    byteDataBuffer.order(ByteOrder.nativeOrder())
    for (i in value.indices) byteDataBuffer.putFloat(value[i])
    val setParameter = AudioEffect::class.java.getMethod(
      "setParameter",
      ByteArray::class.java,
      ByteArray::class.java
    )
    setParameter.invoke(this, arguments, result)
  }
}
