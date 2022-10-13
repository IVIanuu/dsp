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
import androidx.compose.ui.graphics.toArgb
import com.github.michaelbull.result.getOrElse
import com.ivianuu.essentials.AppContext
import com.ivianuu.essentials.AppScope
import com.ivianuu.essentials.android.prefs.DataStoreModule
import com.ivianuu.essentials.app.EsActivity
import com.ivianuu.essentials.app.ScopeWorker
import com.ivianuu.essentials.catch
import com.ivianuu.essentials.coroutines.guarantee
import com.ivianuu.essentials.coroutines.par
import com.ivianuu.essentials.coroutines.parForEach
import com.ivianuu.essentials.data.DataStore
import com.ivianuu.essentials.foreground.ForegroundManager
import com.ivianuu.essentials.foreground.startForeground
import com.ivianuu.essentials.lerp
import com.ivianuu.essentials.logging.Logger
import com.ivianuu.essentials.logging.log
import com.ivianuu.essentials.util.BroadcastsFactory
import com.ivianuu.essentials.util.NotificationFactory
import com.ivianuu.injekt.Inject
import com.ivianuu.injekt.Provide
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.Serializable

@Provide fun musicSessionWorker(
  audioSessionPref: DataStore<AudioSessionPrefs>,
  broadcastsFactory: BroadcastsFactory,
  context: AppContext,
  dspPref: DataStore<DspPrefs>,
  foregroundManager: ForegroundManager,
  logger: Logger,
  notificationFactory: NotificationFactory
) = ScopeWorker<AppScope> {
  // reset eq pref
  dspPref.updateData {
    copy(eq = EqBands.associateWith { band -> eq[band] ?: 0.5f })
  }

  par(
    {
      foregroundManager.startForeground(
        1,
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
      )
    },
    {
      combine(dspPref.data, audioSessions()) { a, b -> a to b }
        .collectLatest { (prefs, audioSessions) ->
          audioSessions.values.parForEach { audioSession ->
            audioSession.apply(prefs)
          }
        }
    }
  )
}

@Serializable data class AudioSessionPrefs(
  val knownAudioSessions: Set<Int> = emptySet()
) {
  companion object {
    @Provide val prefModule = DataStoreModule("audio_session_prefs") { AudioSessionPrefs() }
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
              val session = catch { AudioSession(sessionId) }.getOrElse {
                // second time works lol:D
                catch {
                  AudioSession(sessionId)
                    .also { it.needsResync = true }
                }
                  .getOrElse {
                    catch {
                      AudioSession(sessionId)
                        .also { it.needsResync = true }
                    }
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

  var needsResync = false

  init {
    log { "$sessionId -> start" }
  }

  suspend fun apply(prefs: DspPrefs) {
    log { "$sessionId apply prefs -> $prefs" }

    // enable
    if (needsResync) {
      bassBoost.enabled = false
      equalizer.enabled = false
      needsResync = false
      delay(1000)
    }

    bassBoost.enabled = prefs.dspEnabled
    equalizer.enabled = prefs.dspEnabled

    // bass boost
    bassBoost.setStrength((1000 * prefs.bassBoost).toInt().toShort())

    // eq
    prefs.eq
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
  }

  fun release() {
    log { "$sessionId -> stop" }
    catch { bassBoost.release() }
    catch { equalizer.release() }
  }
}
