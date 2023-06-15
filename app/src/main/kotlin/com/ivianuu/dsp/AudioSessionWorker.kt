/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.dsp

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.toArgb
import com.ivianuu.essentials.AppContext
import com.ivianuu.essentials.AppScope
import com.ivianuu.essentials.app.EsActivity
import com.ivianuu.essentials.app.ScopeWorker
import com.ivianuu.essentials.catch
import com.ivianuu.essentials.compose.bind
import com.ivianuu.essentials.compose.launchComposedEmitter
import com.ivianuu.essentials.coroutines.guarantee
import com.ivianuu.essentials.coroutines.parForEach
import com.ivianuu.essentials.data.DataStore
import com.ivianuu.essentials.foreground.ForegroundManager
import com.ivianuu.essentials.lerp
import com.ivianuu.essentials.logging.Logger
import com.ivianuu.essentials.logging.log
import com.ivianuu.essentials.onFailure
import com.ivianuu.essentials.onSuccess
import com.ivianuu.essentials.ui.AppColors
import com.ivianuu.essentials.util.BroadcastsFactory
import com.ivianuu.essentials.util.NotificationFactory
import com.ivianuu.injekt.Inject
import com.ivianuu.injekt.Provide
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

@Provide fun audioSessionWorker(
  appColors: AppColors,
  broadcastsFactory: BroadcastsFactory,
  configRepository: ConfigRepository,
  context: AppContext,
  foregroundManager: ForegroundManager,
  logger: Logger,
  notificationFactory: NotificationFactory,
  pref: DataStore<DspPrefs>
) = ScopeWorker<AppScope> {
  // reset eq pref
  pref.updateData {
    fun Config.fix() = copy(eq = EqBands.associateWith { band -> eq[band] ?: 0.5f })
    copy(
      currentConfig = currentConfig.fix(),
      configs = configs.mapValues { it.value.fix() }
    )
  }

  foregroundManager.runInForeground(
    notificationFactory(
      "foreground",
      "Foreground",
      NotificationManager.IMPORTANCE_LOW
    ) {
      setContentTitle("DSP")
      setSmallIcon(R.drawable.ic_graphic_eq)
      setContentIntent(
        PendingIntent.getActivity(
          context,
          1,
          Intent(context, EsActivity::class.java),
          PendingIntent.FLAG_UPDATE_CURRENT or
              PendingIntent.FLAG_IMMUTABLE
        )
      )
      color = appColors.primary.toArgb()
    }
  ) {
    launchComposedEmitter(emitter = {}) {
      val enabled = pref.data
        .map { it.dspEnabled }
        .distinctUntilChanged()
        .onEach { println("dsp enabled changed $it") }
        .bind(null) ?: return@launchComposedEmitter
      val config = configRepository.currentConfig
        .onEach { println("config changed $it") }
        .bind(null) ?: return@launchComposedEmitter
      val audioSessions = audioSessions()
        .onEach { println("audio sessions changed $it") }
        .bind(emptyMap())
      LaunchedEffect(enabled, config, audioSessions) {
        audioSessions.values.parForEach { audioSession ->
          audioSession.apply(enabled, config)
        }
      }
    }

    awaitCancellation()
  }
}

private fun audioSessions(
  @Inject broadcastsFactory: BroadcastsFactory,
  @Inject logger: Logger,
  @Inject audioSessionPref: DataStore<DspPrefs>
): Flow<Map<Int, AudioSession>> = channelFlow {
  val audioSessions = mutableMapOf<Int, AudioSession>()

  guarantee(
    {
      broadcastsFactory(
        AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION,
        AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION
      )
        .map {
          when (it.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> AudioSessionEvent.START
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> AudioSessionEvent.STOP
            else -> throw AssertionError()
          } to it.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        }
        .onStart {
          audioSessionPref.data.first().lastAudioSessionId
            ?.let { emit(AudioSessionEvent.START to it) }
        }
        .collect { (event, sessionId) ->
          when (event) {
            AudioSessionEvent.START -> {
              var session: AudioSession? = null
              var attempt = 0

              while (session == null && attempt < 5) {
                catch { AudioSession(sessionId) }
                  .onFailure { it.printStackTrace() }
                  .onSuccess { session = it }

                attempt++
              }

              if (session != null) {
                audioSessions[sessionId] = session!!
                send(audioSessions.toMap())
                audioSessionPref.updateData { copy(lastAudioSessionId = sessionId) }
              } else {
                audioSessionPref.updateData {
                  copy(lastAudioSessionId = null)
                }
              }
            }
            AudioSessionEvent.STOP -> {
              audioSessions.remove(sessionId)
                ?.also { it.release() }
              audioSessionPref.updateData {
                copy(
                  lastAudioSessionId = if (sessionId == lastAudioSessionId) null
                  else lastAudioSessionId
                )
              }
              send(audioSessions.toMap())
            }
          }
        }
    },
    {
      logger.log { "stop all sessions $audioSessions" }
      audioSessions.values.forEach { it.release() }
    }
  )
}

private enum class AudioSessionEvent {
  START, STOP
}

class AudioSession(
  private val sessionId: Int,
  @Inject val logger: Logger
) {
  private val jamesDSP = try {
    AudioEffect::class.java.getConstructor(
      UUID::class.java,
      UUID::class.java, Integer.TYPE, Integer.TYPE
    ).newInstance(EFFECT_TYPE_CUSTOM, EFFECT_TYPE_JAMES_DSP, 0, sessionId)
  } catch (e: Throwable) {
    // todo injekt bug
    with(logger) {
      logger.log { "$sessionId couln't create" }
    }
    throw IllegalStateException("Couldn't create effect for $sessionId")
  }

  init {
    logger.log { "$sessionId -> start" }
  }

  suspend fun apply(enabled: Boolean, config: Config) {
    logger.log { "$sessionId apply config -> enabled $enabled $config" }

    jamesDSP.enabled = enabled

    // eq switch
    setParameterShort(1202, 1)

    // eq levels
    val sortedEq = config.eq
      .toList()
      .sortedBy { it.first }

    val eqLevels = (sortedEq.map { it.first } +
        sortedEq.map { (_, value) -> lerp(-EQ_DB, EQ_DB, value) }).toFloatArray()

    logger.log { "eq levels ${eqLevels.contentToString()}" }

    setParameterFloatArray(116, floatArrayOf(-1f, -1f) + eqLevels)

    // bass boost switch
    setParameterShort(
      1201,
      if (config.bassBoost > 0) 1 else 0
    )

    // bass boost gain
    setParameterShort(112, (BASS_BOOST_DB * config.bassBoost).toInt().toShort())
  }

  fun release() {
    logger.log { "$sessionId -> stop" }
    catch { jamesDSP.release() }
      .onFailure { it.printStackTrace() }
  }

  private fun setParameterShort(parameter: Int, value: Short) {
    try {
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
    } catch (e: Exception) {
      throw RuntimeException(e)
    }
  }

  private fun setParameterFloatArray(parameter: Int, value: FloatArray) {
    try {
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
    } catch (e: Exception) {
      throw RuntimeException(e)
    }
  }

  companion object {
    private val EFFECT_TYPE_CUSTOM = UUID.fromString("f98765f4-c321-5de6-9a45-123459495ab2")
    private val EFFECT_TYPE_JAMES_DSP = UUID.fromString("f27317f4-c984-4de6-9a90-545759495bf2")
  }
}
