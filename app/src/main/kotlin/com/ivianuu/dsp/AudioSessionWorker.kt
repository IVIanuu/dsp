/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.dsp

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.ivianuu.essentials.AppContext
import com.ivianuu.essentials.AppScope
import com.ivianuu.essentials.app.EsActivity
import com.ivianuu.essentials.app.ScopeWorker
import com.ivianuu.essentials.catch
import com.ivianuu.essentials.compose.launchComposition
import com.ivianuu.essentials.coroutines.guarantee
import com.ivianuu.essentials.coroutines.parForEach
import com.ivianuu.essentials.data.DataStore
import com.ivianuu.essentials.foreground.ForegroundManager
import com.ivianuu.essentials.logging.Logger
import com.ivianuu.essentials.logging.log
import com.ivianuu.essentials.onFailure
import com.ivianuu.essentials.onSuccess
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
import kotlinx.coroutines.flow.onStart
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

@Provide fun audioSessionWorker(
  audioSessions: Flow<AudioSessions>,
  configRepository: ConfigRepository,
  context: AppContext,
  foregroundManager: ForegroundManager,
  notificationFactory: NotificationFactory,
  pref: DataStore<DspPrefs>
) = ScopeWorker<AppScope> {
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
    }
  ) {
    launchComposition {
      val enabled by produceState(false) {
        pref.data
          .map { it.dspEnabled }
          .distinctUntilChanged()
          .collect { value = it }
      }

      val config = produceState<Config?>(null) {
        configRepository.currentConfig.collect { value = it }
      }.value ?: return@launchComposition

      val audioSessions by produceState(AudioSessions(emptyMap())) {
        audioSessions.collect { value = it }
      }

      LaunchedEffect(enabled, config, audioSessions) {
        audioSessions.value.values.parForEach { audioSession ->
          catch { audioSession.apply(enabled, config) }
            .onFailure { it.printStackTrace() }
        }
      }
    }

    awaitCancellation()
  }
}

@JvmInline value class AudioSessions(val value: Map<Int, AudioSession>)

@Provide fun audioSessions(
  broadcastsFactory: BroadcastsFactory,
  logger: Logger,
  pref: DataStore<DspPrefs>
): Flow<AudioSessions> = channelFlow {
  val audioSessions = mutableMapOf<Int, AudioSession>()

  guarantee(
    {
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
          when (event) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
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
                send(AudioSessions(audioSessions.toMap()))
                pref.updateData { copy(lastAudioSessionId = sessionId) }
              } else {
                pref.updateData {
                  copy(lastAudioSessionId = null)
                }
              }
            }
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
              audioSessions.remove(sessionId)
                ?.also { it.release() }
              pref.updateData {
                copy(
                  lastAudioSessionId = if (sessionId == lastAudioSessionId) null
                  else lastAudioSessionId
                )
              }
              send(AudioSessions(audioSessions.toMap()))
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
    val sortedEq = config.eqDb
      .toList()
      .sortedBy { it.first }

    val eqLevels = (sortedEq.map { it.first.toFloat() } +
        sortedEq.map { (_, value) -> value.toFloat() }).toFloatArray()

    logger.log { "eq levels ${eqLevels.contentToString()}" }

    setParameterFloatArray(116, floatArrayOf(-1f,  -1f) + eqLevels)

    // bass boost switch
    setParameterShort(
      1201,
      if (config.bassBoostDb > 0) 1 else 0
    )

    // bass boost gain
    setParameterShort(112, config.bassBoostDb.toShort())

    // post gain
    setParameterFloatArray(
      1500,
      floatArrayOf(-0.1f, 60f, config.postGainDb.toFloat())
    )
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
