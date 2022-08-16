/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.dsp

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.AudioEffect
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

@Provide fun musicSessionWorker(
  audioSessionPref: DataStore<AudioSessionPrefs>,
  broadcastsFactory: BroadcastsFactory,
  context: AppContext,
  dspPref: DataStore<DspPrefs>,
  foregroundManager: ForegroundManager,
  logger: Logger,
  notificationFactory: NotificationFactory
) = ScopeWorker<AppScope> {
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
        .collect { (prefs, audioSessions) ->
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
                  .getOrElse { null }
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
  private val jamesDSP = try {
    AudioEffect::class.java.getConstructor(
      UUID::class.java,
      UUID::class.java, Integer.TYPE, Integer.TYPE
    ).newInstance(EFFECT_TYPE_CUSTOM, EFFECT_TYPE_JAMES_DSP, 0, sessionId)
  } catch (e: Throwable) {
    // todo injekt bug
    log(logger = logger) { "$sessionId couln't create" }
    throw IllegalStateException("Couldn't create effect for $sessionId")
  }

  var needsResync = false

  init {
    log { "$sessionId -> start" }
  }

  suspend fun apply(prefs: DspPrefs) {
    log { "$sessionId apply prefs -> $prefs" }

    // enable
    if (needsResync) {
      jamesDSP.enabled = false
      needsResync = false
      delay(1000)
    }

    jamesDSP.enabled = prefs.dspEnabled

    // eq switch
    setParameterShort(1202, 1)

    val eqGain = 20f

    // eq levels
    val jamesDspEqValues = JamesEqBands
      .map { jamesEqBand ->
        val lowerAnchorBand = EqBands.lastOrNull { it <= jamesEqBand }
        val upperAnchorBand = EqBands.firstOrNull { it >= jamesEqBand }

        val lowerAnchorValue = prefs.eq[lowerAnchorBand] ?: 0.5f
        val upperAnchorValue = prefs.eq[upperAnchorBand] ?: 0.5f

        val jamesBandFraction = calcFraction(
          lowerAnchorBand ?: jamesEqBand,
          upperAnchorBand ?: jamesEqBand,
          jamesEqBand
        )

        val jamesEqValue = lerp(lowerAnchorValue, upperAnchorValue, jamesBandFraction)

        jamesEqBand to jamesEqValue to lerp(-eqGain, eqGain, jamesEqValue)
      }
      .sortedBy { it.first.first }

    log { "james eq $jamesDspEqValues" }

    val eqLevels = (jamesDspEqValues.map { it.first.first } +
        jamesDspEqValues.map { (_, value) -> value }).toFloatArray()

    val filtertype = -1f
    val interpolationMode = -1f

    val ftype = floatArrayOf(filtertype, interpolationMode)
    val sendAry = mergeFloatArray(ftype, eqLevels)

    log { "eq levels ${eqLevels.contentToString()}" }

    setParameterFloatArray(116, sendAry)

    // bass boost switch
    setParameterShort(
      1201,
      if (prefs.bassBoost > 0) 1 else 0
    )

    // bass boost gain
    setParameterShort(112, (15 * prefs.bassBoost).toInt().toShort())
  }

  fun release() {
    log { "$sessionId -> stop" }
    jamesDSP.release()
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

  private fun mergeFloatArray(vararg arrays: FloatArray): FloatArray {
    var size = 0
    for (a: FloatArray in arrays) size += a.size
    val res = FloatArray(size)
    var destPos = 0
    for (i in arrays.indices) {
      if (i > 0) destPos += arrays[i - 1].size
      val length = arrays[i].size
      System.arraycopy(arrays[i], 0, res, destPos, length)
    }
    return res
  }

  companion object {
    private val EFFECT_TYPE_CUSTOM = UUID.fromString("f98765f4-c321-5de6-9a45-123459495ab2")
    private val EFFECT_TYPE_JAMES_DSP = UUID.fromString("f27317f4-c984-4de6-9a90-545759495bf2")
  }
}

private fun calcFraction(a: Float, b: Float, pos: Float) =
  (if (b - a == 0f) 0f else (pos - a) / (b - a)).coerceIn(0f, 1f)
