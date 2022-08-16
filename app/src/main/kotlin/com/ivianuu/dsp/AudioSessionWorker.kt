package com.ivianuu.dsp

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.compose.ui.graphics.toArgb
import com.ivianuu.essentials.AppContext
import com.ivianuu.essentials.AppScope
import com.ivianuu.essentials.app.EsActivity
import com.ivianuu.essentials.app.ScopeWorker
import com.ivianuu.essentials.coroutines.guarantee
import com.ivianuu.essentials.coroutines.par
import com.ivianuu.essentials.data.DataStore
import com.ivianuu.essentials.foreground.ForegroundManager
import com.ivianuu.essentials.foreground.startForeground
import com.ivianuu.essentials.logging.Logger
import com.ivianuu.essentials.logging.log
import com.ivianuu.essentials.util.BroadcastsFactory
import com.ivianuu.essentials.util.NotificationFactory
import com.ivianuu.injekt.Inject
import com.ivianuu.injekt.Provide
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import java.util.*

@Provide fun musicSessionWorker(
  broadcastsFactory: BroadcastsFactory,
  context: AppContext,
  foregroundManager: ForegroundManager,
  logger: Logger,
  notificationFactory: NotificationFactory,
  pref: DataStore<DspPrefs>
) = ScopeWorker<AppScope> {
  log { "hello" }
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
          setSmallIcon(R.drawable.es_ic_done)
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
      combine(pref.data, audioSessions()) { a, b -> a to b }
        .collect { (prefs, audioSessions) ->
          audioSessions.values.forEach { audioSession ->
            audioSession.apply(prefs)
          }
        }
    }
  )
}

private fun audioSessions(
  @Inject broadcastsFactory: BroadcastsFactory,
  @Inject logger: Logger
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
        //.onStart { emit(AudioSessionEvent.START to 0) }
        .collect { (event, sessionId) ->
          when (event) {
            AudioSessionEvent.START -> {
              val session = AudioSession(sessionId)
              audioSessions[sessionId] = session
              send(audioSessions.toMap())
            }
            AudioSessionEvent.STOP -> {
              audioSessions.remove(sessionId)
                ?.also { it.release() }
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
    throw IllegalStateException("Couldn't effect for $sessionId")
  }

  init {
    log { "$sessionId -> start" }
  }

  fun apply(prefs: DspPrefs) {
    log { "$sessionId apply prefs -> $prefs" }

    jamesDSP.enabled = prefs.dspEnabled

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

  companion object {
    private val EFFECT_TYPE_CUSTOM = UUID.fromString("f98765f4-c321-5de6-9a45-123459495ab2")
    private val EFFECT_TYPE_JAMES_DSP = UUID.fromString("f27317f4-c984-4de6-9a90-545759495bf2")
  }
}
