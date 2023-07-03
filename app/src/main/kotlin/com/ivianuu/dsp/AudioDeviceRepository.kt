package com.ivianuu.dsp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.media.AudioManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.ivianuu.essentials.AppContext
import com.ivianuu.essentials.AppScope
import com.ivianuu.essentials.Scoped
import com.ivianuu.essentials.cast
import com.ivianuu.essentials.compose.compositionFlow
import com.ivianuu.essentials.coroutines.RefCountedResource
import com.ivianuu.essentials.coroutines.ScopedCoroutineScope
import com.ivianuu.essentials.coroutines.withResource
import com.ivianuu.essentials.logging.Logger
import com.ivianuu.essentials.permission.PermissionManager
import com.ivianuu.essentials.result.catch
import com.ivianuu.essentials.safeAs
import com.ivianuu.essentials.time.seconds
import com.ivianuu.essentials.util.BroadcastsFactory
import com.ivianuu.injekt.Provide
import com.ivianuu.injekt.android.SystemService
import com.ivianuu.injekt.common.typeKeyOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

sealed interface AudioDevice {
  val id: String
  val name: String

  object Phone : AudioDevice {
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
  val currentAudioDevice: Flow<AudioDevice> = compositionFlow {
    if (!permissionManager.permissionState(dspPermissions).collectAsState(false).value)
      return@compositionFlow AudioDevice.Phone

    val connectedBluetoothDevice by produceState<AudioDevice?>(null) {
      broadcastsFactory(
        BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
        BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED,
        "android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED"
      )
        .onStart<Any?> { emit(Unit) }
        .mapLatest {
          if (!audioManager.isBluetoothA2dpOn) null
          else a2Dp.withResource(Unit) {
            it.javaClass.getDeclaredMethod("getActiveDevice")
              .invoke(it)
              .safeAs<BluetoothDevice?>()
              ?.let { AudioDevice.Bluetooth(it.address, it.alias ?: it.name) }
          }
        }
        .collect { value = it }
    }

    if (connectedBluetoothDevice != null) connectedBluetoothDevice!!
    else AudioDevice.Phone
  }

  private val a2Dp = RefCountedResource<Unit, BluetoothA2dp>(
    timeout = 2.seconds,
    scope = scope,
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