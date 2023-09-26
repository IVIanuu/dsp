/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.dsp

import android.media.audiofx.AudioEffect
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ivianuu.essentials.AppScope
import com.ivianuu.essentials.app.ScopeComposition
import com.ivianuu.essentials.data.DataStore
import com.ivianuu.essentials.foreground.ForegroundManager
import com.ivianuu.essentials.logging.Logger
import com.ivianuu.essentials.logging.log
import com.ivianuu.essentials.result.catch
import com.ivianuu.essentials.result.getOrThrow
import com.ivianuu.essentials.result.onFailure
import com.ivianuu.essentials.result.onSuccess
import com.ivianuu.essentials.result.printErrors
import com.ivianuu.essentials.util.BroadcastsFactory
import com.ivianuu.injekt.Inject
import com.ivianuu.injekt.Provide
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

@Provide fun audioSessionFeature(
  audioDeviceRepository: AudioDeviceRepository,
  broadcastsFactory: BroadcastsFactory,
  configRepository: ConfigRepository,
  foregroundManager: ForegroundManager,
  @Inject logger: Logger,
  pref: DataStore<DspPrefs>
) = ScopeComposition<AppScope> {
  val enabled by remember { pref.data.map { it.dspEnabled } }.collectAsState(false)

  LaunchedEffect(true) {
    foregroundManager.startForeground()
  }

  var audioSessionIds by remember { mutableStateOf(listOf<Int>()) }
  LaunchedEffect(true) {
    broadcastsFactory(
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
          AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> audioSessionIds + sessionId
          AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> audioSessionIds - sessionId
          else -> audioSessionIds
        }
      }
  }

  val audioSessions = audioSessionIds
    .mapNotNull { sessionId ->
      key(sessionId) {
        val session = remember {
          var session: AudioSession? = null
          var attempt = 0

          while (session == null && attempt < 5) {
            catch { AudioSession(sessionId) }
              .onFailure { it.printStackTrace() }
              .onSuccess { session = it }
            attempt++
          }

          // this session seems to be broken
          if (session == null)
            audioSessionIds = audioSessionIds - sessionId

          session
        }

        if (session != null)
          LaunchedEffect(true) {
            pref.updateData { copy(lastAudioSessionId = sessionId) }
          }

        session
      }
    }

  val config = produceState<DspConfig?>(null) {
    audioDeviceRepository.currentAudioDevice
      .onEach { logger.log { "current device changed $it" } }
      .flatMapLatest { configRepository.deviceConfig(it.id) }
      .collect { value = it }
  }.value ?: return@ScopeComposition

  audioSessions.forEach { audioSession ->
    key(audioSession.sessionId) {
      audioSession.Apply(enabled, config)

      DisposableEffect(true) {
        onDispose { audioSession.release() }
      }
    }
  }
}

class AudioSession(val sessionId: Int, @Inject val logger: Logger) {
  private val jamesDSP = catch {
    AudioEffect::class.java.getConstructor(
      UUID::class.java,
      UUID::class.java, Integer.TYPE, Integer.TYPE
    ).newInstance(EFFECT_TYPE_CUSTOM, EFFECT_TYPE_JAMES_DSP, 0, sessionId)
  }
    .printErrors()
    .getOrThrow()

  init {
    logger.log { "$sessionId -> start" }
  }

  @Composable fun Apply(enabled: Boolean, config: DspConfig) {
    LaunchedEffect(enabled) {
      logger.log { "$sessionId update enabled $enabled" }
      jamesDSP.enabled = enabled
    }

    LaunchedEffect(true) {
      // eq switch
      setParameterShort(1202, 1)
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
    LaunchedEffect(animatedEq) {
      previousEq = config.eqDb

      val sortedEq = animatedEq
        .toList()
        .sortedBy { it.first }

      val eqLevels = (sortedEq.map { it.first.toFloat() } +
          sortedEq.map { (_, value) -> value.toFloat() }).toFloatArray()

      logger.log { "$sessionId update eq ${eqLevels.contentToString()}" }
      setParameterFloatArray(116, floatArrayOf(-1f,  -1f) + eqLevels)
    }

    // bass boost switch
    LaunchedEffect(true) { setParameterShort(1201, 1) }

    // bass boost gain
    LaunchedEffect(config.bassBoostDb) {
      logger.log { "$sessionId update bass boost ${config.bassBoostDb}" }
      setParameterShort(112, config.bassBoostDb.toShort())
    }
  }

  fun release() {
    logger.log { "$sessionId -> stop" }
    catch { jamesDSP.release() }
      .onFailure { it.printStackTrace() }
  }

  private fun setParameterShort(parameter: Int, value: Short) {
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
    setParameter.invoke(jamesDSP, arguments, result)
  }

  private fun setParameterFloatArray(parameter: Int, value: FloatArray) {
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
    setParameter.invoke(jamesDSP, arguments, result)
  }

  companion object {
    private val EFFECT_TYPE_CUSTOM = UUID.fromString("f98765f4-c321-5de6-9a45-123459495ab2")
    private val EFFECT_TYPE_JAMES_DSP = UUID.fromString("f27317f4-c984-4de6-9a90-545759495bf2")
  }
}
