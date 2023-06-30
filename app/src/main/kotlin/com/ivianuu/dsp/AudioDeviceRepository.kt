package com.ivianuu.dsp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.ivianuu.essentials.AppContext
import com.ivianuu.essentials.AppScope
import com.ivianuu.essentials.Scoped
import com.ivianuu.essentials.cast
import com.ivianuu.essentials.compose.compositionFlow
import com.ivianuu.essentials.coroutines.RefCountedResource
import com.ivianuu.essentials.coroutines.ScopedCoroutineScope
import com.ivianuu.essentials.coroutines.timerFlow
import com.ivianuu.essentials.coroutines.withResource
import com.ivianuu.essentials.logging.Logger
import com.ivianuu.essentials.logging.log
import com.ivianuu.essentials.permission.PermissionManager
import com.ivianuu.essentials.result.catch
import com.ivianuu.essentials.safeAs
import com.ivianuu.essentials.time.seconds
import com.ivianuu.essentials.util.BroadcastsFactory
import com.ivianuu.injekt.Provide
import com.ivianuu.injekt.android.SystemService
import com.ivianuu.injekt.common.typeKeyOf
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
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
  object Aux : AudioDevice {
    override val name: String get() = "Aux"
    override val id: String get() = "audio_device_aux"
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
  private val logger: Logger,
  permissionManager: PermissionManager,
  scope: ScopedCoroutineScope<AppScope>
) {
  val currentAudioDevice: Flow<AudioDevice> = compositionFlow {
    val isHeadsetConnected by produceState(false) {
      broadcastsFactory(AudioManager.ACTION_HEADSET_PLUG)
        .onStart<Any?> { emit(Unit) }
        .map { audioManager.isWiredHeadsetOn }
        .collect { value = it }
    }

    val connectedBluetoothDevice by produceState<AudioDevice?>(null) {
      broadcastsFactory(
        BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
        BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED,
        "android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED"
      )
        .onStart<Any?> { emit(Unit) }
        .mapLatest {
          if (!audioManager.isBluetoothA2dpOn) flowOf(null)
          else proxy.withResource(Unit) {
            it.javaClass.getDeclaredMethod("getActiveDevice")
              .invoke(it)
              .safeAs<BluetoothDevice?>()
              ?.address
          }
        }
        .flatMapLatest { address ->
          if (address == null) flowOf(null)
          else audioDevices.map { it.singleOrNull { it.id == address } }
        }
        .collect { value = it }
    }

    if (connectedBluetoothDevice != null) connectedBluetoothDevice!!
    else if (isHeadsetConnected) AudioDevice.Aux
    else AudioDevice.Phone
  }

  @SuppressLint("MissingPermission")
  val audioDevices: Flow<List<AudioDevice>> =
    permissionManager.permissionState(listOf(typeKeyOf<DspBluetoothConnectPermission>()))
      .flatMapLatest {
        if (!it) flowOf(emptyList())
        else bondedDeviceChanges()
          .onStart<Any> { emit(Unit) }
          .map {
            bluetoothManager.adapter?.bondedDevices
              ?.map { AudioDevice.Bluetooth(it.address, it.alias ?: it.name) }
              ?: emptyList()
          }
      }
      .map { it + AudioDevice.Phone + AudioDevice.Aux }
      .distinctUntilChanged()

  private val proxy = RefCountedResource<Unit, BluetoothA2dp>(
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

  fun isAudioDeviceConnected(id: String): Flow<Boolean> = audioDevices
    .map { it.single { it.id == id } }
    .flatMapConcat {
      when (it) {
        AudioDevice.Aux -> flowOf(false)
        is AudioDevice.Bluetooth -> bondedDeviceChanges()
          .onStart<Any> { emit(Unit) }
          .map { id.isConnected() }
          .distinctUntilChanged()
        AudioDevice.Phone -> flowOf(true)
      }
    }

  private fun String.isConnected(): Boolean =
    bluetoothManager.adapter.getRemoteDevice(this)
      ?.let {
        BluetoothDevice::class.java.getDeclaredMethod("isConnected").invoke(it) as Boolean
      } ?: false

  private fun bondedDeviceChanges() = broadcastsFactory(
    BluetoothAdapter.ACTION_STATE_CHANGED,
    BluetoothDevice.ACTION_BOND_STATE_CHANGED,
    BluetoothDevice.ACTION_ACL_CONNECTED,
    BluetoothDevice.ACTION_ACL_DISCONNECTED
  )
}
