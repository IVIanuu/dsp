/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.dsp

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import androidx.compose.ui.graphics.toArgb
import com.ivianuu.essentials.AppContext
import com.ivianuu.essentials.AppScope
import com.ivianuu.essentials.android.prefs.PrefModule
import com.ivianuu.essentials.app.EsActivity
import com.ivianuu.essentials.app.ScopeWorker
import com.ivianuu.essentials.catch
import com.ivianuu.essentials.coroutines.combine
import com.ivianuu.essentials.coroutines.guarantee
import com.ivianuu.essentials.coroutines.parForEach
import com.ivianuu.essentials.data.DataStore
import com.ivianuu.essentials.foreground.ForegroundManager
import com.ivianuu.essentials.getOrElse
import com.ivianuu.essentials.lerp
import com.ivianuu.essentials.logging.Logger
import com.ivianuu.essentials.logging.log
import com.ivianuu.essentials.onFailure
import com.ivianuu.essentials.util.BroadcastsFactory
import com.ivianuu.essentials.util.NotificationFactory
import com.ivianuu.injekt.Inject
import com.ivianuu.injekt.Provide
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

@Provide fun audioSessionWorker(
  audioSessionPref: DataStore<AudioSessionPrefs>,
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
    notificationFactory.build(
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
          PendingIntent.FLAG_UPDATE_CURRENT
        )
      )
      color = DspTheme.Primary.toArgb()
    }
  ) {
    combine(
      pref.data
        .map { it.dspEnabled }
        .distinctUntilChanged(),
      configRepository.currentConfig,
      audioSessions()
    )
      .collectLatest { (enabled, config, audioSessions) ->
        audioSessions.values.parForEach { audioSession ->
          audioSession.apply(enabled, config)
        }
      }
  }
}

@Serializable data class AudioSessionPrefs(
  val knownAudioSessions: Set<Int> = emptySet()
) {
  companion object {
    @Provide val prefModule = PrefModule { AudioSessionPrefs() }
  }
}

private fun audioSessions(
  @Inject broadcastsFactory: BroadcastsFactory,
  @Inject logger: Logger,
  @Inject audioSessionPref: DataStore<AudioSessionPrefs>
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
          audioSessionPref.data.first().knownAudioSessions.forEach {
            emit(AudioSessionEvent.START to it)
          }
        }
        .collect { (event, sessionId) ->
          when (event) {
            AudioSessionEvent.START -> {
              val session = catch { AudioSession(sessionId) }
                .onFailure { it.printStackTrace() }
                .getOrElse {
                  // second time works lol:D
                  catch {
                    AudioSession(sessionId)
                      .also { it.needsResync = true }
                  }
                    .onFailure { it.printStackTrace() }
                    .getOrElse {
                      catch {
                        AudioSession(sessionId)
                          .also { it.needsResync = true }
                      }
                        .onFailure { it.printStackTrace() }
                        .getOrElse { null }
                    }
                }
              if (session != null) {
                audioSessions[sessionId] = session
                send(audioSessions.toMap())
                audioSessionPref.updateData {
                  copy(
                    knownAudioSessions = knownAudioSessions.toMutableSet().apply { add(sessionId) })
                }
              } else {
                audioSessionPref.updateData {
                  copy(
                    knownAudioSessions = knownAudioSessions.toMutableSet()
                      .apply { remove(sessionId) })
                }
              }
            }
            AudioSessionEvent.STOP -> {
              audioSessions.remove(sessionId)
                ?.also { it.release() }
              audioSessionPref.updateData {
                copy(
                  knownAudioSessions = knownAudioSessions.toMutableSet()
                    .apply { remove(sessionId) })
              }
              send(audioSessions.toMap())
            }
          }
        }
    },
    {
      log { "stop all sessions $audioSessions" }
      audioSessions.values.forEach { it.release() }
    }
  )
}

private enum class AudioSessionEvent {
  START, STOP
}

class AudioSession(private val sessionId: Int, @Inject val logger: Logger) {
  private val bassBoost = BassBoost(0, sessionId)
  private val equalizer = Equalizer(0, sessionId)
  private val loudnessEnhancer = LoudnessEnhancer(sessionId)

  var needsResync = false

  init {
    log { "$sessionId -> start" }
  }

  suspend fun apply(enabled: Boolean, config: Config) {
    log { "$sessionId apply config -> enabled $enabled $config" }

    // enable
    if (needsResync) {
      bassBoost.enabled = false
      equalizer.enabled = false
      loudnessEnhancer.enabled = false
      needsResync = false
      delay(1000)
    }

    bassBoost.enabled = enabled
    equalizer.enabled = enabled
    loudnessEnhancer.enabled = enabled

    // bass boost
    bassBoost.setStrength((config.bassBoost * BASS_BOOST_DB * 100).toInt().toShort())

    // eq
    config.eq
      .toList()
      .sortedBy { it.first }
      .forEachIndexed { index, pair ->
        equalizer.setBandLevel(
          index.toShort(),
          lerp(
            equalizer.bandLevelRange[0].toFloat(),
            equalizer.bandLevelRange[1].toFloat(),
            pair.second
          )
            .toInt()
            .toShort()
        )
      }

    loudnessEnhancer.setTargetGain((config.postGain * POST_GAIN_DB * 100).toInt())
  }

  fun release() {
    log { "$sessionId -> stop" }
    bassBoost.release()
    equalizer.release()
    loudnessEnhancer.release()
  }
}
