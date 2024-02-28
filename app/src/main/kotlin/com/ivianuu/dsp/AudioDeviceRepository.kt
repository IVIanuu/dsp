package com.ivianuu.dsp

import android.bluetooth.*
import android.media.*
import app.cash.molecule.*
import com.ivianuu.essentials.*
import com.ivianuu.essentials.compose.*
import com.ivianuu.essentials.coroutines.*
import com.ivianuu.essentials.permission.*
import com.ivianuu.essentials.util.*
import com.ivianuu.injekt.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.coroutines.*

sealed interface AudioDevice {
  val id: String
  val name: String

  data object Phone : AudioDevice {
    override val name: String get() = "Phone speaker"
    override val id: String get() = "audio_device_phone"
  }
  data class Bluetooth(val address: String, override val name: String) : AudioDevice {
    override val id: String get() = address
  }
}

@Provide @Scoped<AppScope> class AudioDeviceRepository(
  private val audioManager: @SystemService AudioManager,
  private val appContext: AppContext,
  private val broadcastsFactory: BroadcastsFactory,
  private val bluetoothManager: @SystemService BluetoothManager,
  permissionManager: PermissionManager,
  scope: ScopedCoroutineScope<AppScope>
) {
  val currentAudioDevice: Flow<AudioDevice> = moleculeFlow(RecompositionMode.Immediate) {
    if (!permissionManager.permissionState(dspPermissions).collect(false))
      AudioDevice.Phone
    else broadcastsFactory(
      BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
      BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED,
      "android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED"
    )
      .onStart<Any?> { emit(Unit) }
      .mapLatest {
        if (!audioManager.isBluetoothA2dpOn) null
        else a2Dp.use(Unit) {
          it.javaClass.getDeclaredMethod("getActiveDevice")
            .invoke(it)
            .safeAs<BluetoothDevice?>()
            ?.let { AudioDevice.Bluetooth(it.address, it.alias ?: it.name) }
        }
      }
      .collect(null)
      ?: AudioDevice.Phone
  }

  private val a2Dp = scope.sharedResource<Unit, BluetoothA2dp>(
    sharingStarted = SharingStarted.WhileSubscribed(1000, 0),
    create = {
      suspendCancellableCoroutine { cont ->
        bluetoothManager.adapter.getProfileProxy(
          appContext,
          object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
              catch { cont.resume(proxy.cast()) }
            }

            override fun onServiceDisconnected(profile: Int) {
            }
          },
          BluetoothProfile.A2DP
        )
      }
    },
    release = { _, proxy ->
      catch {
        bluetoothManager.adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
      }
    }
  )
}
