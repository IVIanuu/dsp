/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.dsp

/**
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.audiofx.AudioEffect
import android.util.Log
import android.widget.Toast
import java.io.BufferedReader
import java.io.FileReader
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.*
import kotlin.math.ceil

class JDSPModule(sessionId: Int) {
val EFFECT_TYPE_CUSTOM = UUID.fromString("f98765f4-c321-5de6-9a45-123459495ab2")
val EFFECT_JAMESDSP = UUID.fromString("f27317f4-c984-4de6-9a90-545759495bf2")

val jamesDSP = AudioEffect::class.java.getConstructor(
UUID::class.java,
UUID::class.java, Integer.TYPE, Integer.TYPE
).newInstance(EFFECT_TYPE_CUSTOM, EFFECT_JAMESDSP, 0, sessionId)

fun release() {
jamesDSP.release()
}

fun setParameterIntArray(parameter: Int, value: IntArray) {
try {
val arguments = byteArrayOf(
parameter.toByte(), (parameter shr 8).toByte(),
(parameter shr 16).toByte(), (parameter shr 24).toByte()
)
val result = IntToByte(value)
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

fun setParameterFloatArray(parameter: Int, value: FloatArray) {
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

fun setParameterCharArray(parameter: Int, value: String) {
try {
val arguments = byteArrayOf(
parameter.toByte(), (parameter shr 8).toByte(),
(parameter shr 16).toByte(), (parameter shr 24).toByte()
)
var result: ByteArray? = value.toByteArray(Charset.forName("US-ASCII"))
if (result!!.size < 256) {
val zeroPad = 256 - result.size
var zeroArray: ByteArray? = ByteArray(zeroPad)
result = concatArrays(result, zeroArray!!)
zeroArray = null
}
val setParameter = AudioEffect::class.java.getMethod(
"setParameter",
ByteArray::class.java,
ByteArray::class.java
)
setParameter.invoke(jamesDSP, arguments, result)
result = null
} catch (e: Exception) {
throw RuntimeException(e)
}
}

private fun setParameterInt(parameter: Int, value: Int) {
try {
val arguments = byteArrayOf(
parameter.toByte(), (parameter shr 8).toByte(),
(parameter shr 16).toByte(), (parameter shr 24).toByte()
)
val result = byteArrayOf(
value.toByte(), (value shr 8).toByte(),
(value shr 16).toByte(), (value shr 24).toByte()
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

fun setParameterShort(parameter: Int, value: Short) {
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

fun getParameter(parameter: Int): Int {
try {
val arguments = byteArrayOf(
parameter.toByte(), (parameter shr 8).toByte(),
(parameter shr 16).toByte(), (parameter shr 24).toByte()
)
val result = ByteArray(4)
val getParameter = AudioEffect::class.java.getMethod(
"getParameter",
ByteArray::class.java,
ByteArray::class.java
)
getParameter.invoke(jamesDSP, arguments, result)
return byteArrayToInt(result)
} catch (e: Exception) {
throw RuntimeException(e)
}
}
}

class HeadsetService : Service() {

private val mAudioSessions: MutableMap<Int, JDSPModule?> = HashMap()
private val eqLevels = FloatArray(30)

private var oldeqText: String? = ""
private var oldEELProgramName: String? = ""
private var oldVDCName: String? = ""
private var oldImpulseName: String? = ""
private var oldImpSet = IntArray(6)
private var oldConvMode = 0
private var prelimthreshold = 0f
private var prelimrelease = 0f
private var prepostgain = 0f
private var preferencesMode: SharedPreferences? = null

var modeEffect = 0

var jamesDspGbEffect: JDSPModule? = null
var dspModuleSamplingRate = 0
var ddcString: String? = ""
var eelProgString: String? = ""

private val mAudioSessionReceiver: BroadcastReceiver = object : BroadcastReceiver() {
override fun onReceive(context: Context, intent: Intent) {
val action = intent.action
val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
if (sessionId == 0) return
if ((action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)) {
if (modeEffect == 0) return
if (!mAudioSessions.containsKey(sessionId)) {
var fxId: JDSPModule? = JDSPModule(sessionId)
if (fxId!!.jamesDSP == null) {
fxId.release()
fxId = null
} else mAudioSessions[sessionId] = fxId
updateDsp(false, true)
}
}
if ((action == AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)) {
var gone = mAudioSessions.remove(sessionId)
gone?.release()
gone = null
}
}
}

override fun onCreate() {
super.onCreate()
val audioFilter = IntentFilter()
audioFilter.addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
audioFilter.addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)

preferencesMode =
getSharedPreferences(DSPManager.SHARED_PREFERENCES_BASENAME.toString() + "." + "settings", 0)
if (!preferencesMode.contains("dsp.app.modeEffect")) preferencesMode.edit()
.putInt("dsp.app.modeEffect", 0).commit()

modeEffect = preferencesMode.getInt("dsp.app.modeEffect", 0)

if (jamesDspGbEffect != null) {
jamesDspGbEffect!!.release()
jamesDspGbEffect = null
}

if (modeEffect == 0) {
if (jamesDspGbEffect == null) jamesDspGbEffect = JDSPModule(0)
if (jamesDspGbEffect!!.jamesDSP == null) {
Toast.makeText(
this@HeadsetService,
"Library load failed(Global effect)",
Toast.LENGTH_SHORT
).show()
jamesDspGbEffect!!.release()
jamesDspGbEffect = null
}
}

updateDsp(true, true)
}

private fun updateDsp(
preferences: SharedPreferences,
session: JDSPModule,
updateMajor: Boolean,
sessionId: Int
) {
var session = session

val masterSwitch = preferences.getBoolean("dsp.masterswitch.enable", false)
session.jamesDSP.enabled = masterSwitch // Master switch
if (masterSwitch) {
val bassBoostEnabled = if (preferences.getBoolean("dsp.bass.enable", false)) 1 else 0
val equalizerEnabled = if (preferences.getBoolean("dsp.tone.enable", false)) 1 else 0
val stringEqEnabled = if (preferences.getBoolean("dsp.streq.enable", false)) 1 else 0
val bs2bEnabled = if (preferences.getBoolean("dsp.bs2b.enable", false)) 1 else 0
val analogModelEnabled =
if (preferences.getBoolean("dsp.analogmodelling.enable", false)) 1 else 0
val numberOfParameterCommitted = session.getParameter(19998)
dspModuleSamplingRate = session.getParameter(20001)
if (dspModuleSamplingRate == 0) {
if (session.getParameter(20002) == 0) {
Toast.makeText(this@HeadsetService, "DSp reboot", Toast.LENGTH_LONG).show()
Log.e("HeadsetService", "Get PID failed from engine")
}
Toast.makeText(this@HeadsetService, "Dsp crashed", Toast.LENGTH_LONG).show()
Log.e("HeadsetService", "Get zero sample rate from engine? Resurrecting service!")
if (modeEffect == 0) {
Log.e("Dsp", "Global audio session have been killed, reload it now!")
jamesDspGbEffect!!.release()
jamesDspGbEffect = JDSPModule(0)
jamesDspGbEffect!!.jamesDSP.enabled = masterSwitch // Master switch
} else {
Log.e(
"Dsp",
"Audio session $sessionId have been killed, reload it now!"
)
session.release()
session = JDSPModule(sessionId)
session.jamesDSP.enabled = masterSwitch // Master switch
}
}
val limthreshold =
java.lang.Float.valueOf(preferences.getString("dsp.masterswitch.limthreshold", "-0.1"))
val limrelease =
java.lang.Float.valueOf(preferences.getString("dsp.masterswitch.limrelease", "60"))
val postgain =
java.lang.Float.valueOf(preferences.getString("dsp.masterswitch.postgain", "0"))
if ((prelimthreshold != limthreshold) || (prelimrelease != limrelease) || (prepostgain != postgain)) session.setParameterFloatArray(
1500, floatArrayOf(limthreshold, limrelease, postgain)
)
prelimthreshold = limthreshold
prelimrelease = limrelease
prepostgain = postgain

val maxAttack =
java.lang.Float.valueOf(preferences.getString("dsp.compression.maxatk", "30"))
val maxRelease =
java.lang.Float.valueOf(preferences.getString("dsp.compression.maxrel", "200"))
val adaptSpeed =
java.lang.Float.valueOf(preferences.getString("dsp.compression.adaptspeed", "800"))
val compConfig = floatArrayOf(maxAttack, maxRelease, adaptSpeed)
session.setParameterFloatArray(115, compConfig)

// compresser enabled
session.setParameterShort(
1200,
0
)

if (bassBoostEnabled == 1) {
val maxg = preferences.getString("dsp.bass.maxgain", "5")!!.toShort()
session.setParameterShort(112, maxg)
}

session.setParameterShort(
1201,
bassBoostEnabled.toShort()
) // Bass boost switch

if (equalizerEnabled == 1) {
/* Equalizer state is in a single string preference with all values separated by ; */
val levels = preferences.getString(
"dsp.tone.eq.custom",
"25.0;40.0;63.0;100.0;160.0;250.0;400.0;630.0;1000.0;1600.0;2500.0;4000.0;6300.0;10000.0;16000.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0"
)!!
.split(";").toTypedArray()
for (i in levels.indices) eqLevels[i] = java.lang.Float.valueOf(levels[i])
var filtertype = -1.0f
if (preferences.getString("dsp.tone.filtertype", "0")!!.toShort().toInt() == 1) filtertype =
1.0f
var interpolationMode = -1.0f
if (preferences.getString("dsp.tone.interpolation", "0")!!.toShort()
.toInt() == 1
) interpolationMode = 1.0f
val ftype = floatArrayOf(filtertype, interpolationMode)
val sendAry = mergeFloatArray(ftype, eqLevels)
session.setParameterFloatArray(116, sendAry)
}
session.setParameterShort(
1202,
equalizerEnabled.toShort()
) // Equalizer switch
var updateNow = true
if (stringEqEnabled == 1 && updateMajor) {
val eqText = preferences.getString("dsp.streq.stringp", "GraphicEQ: 0.0 0.0; ")
if ((oldeqText == eqText) && numberOfParameterCommitted != 0) updateNow = false
if (updateNow) {
oldeqText = eqText
val arraySize2Send = 256
val stringLength = eqText!!.length
val numTime2Send =
Math.ceil(stringLength.toDouble() / arraySize2Send)
.toInt() // Number of times that have to send
session.setParameterIntArray(
8888,
intArrayOf(numTime2Send, arraySize2Send)
) // Send buffer info for module to allocate memory
for (i in 0 until numTime2Send) session.setParameterCharArray(
12001,
eqText.substring(
arraySize2Send * i,
Math.min(arraySize2Send * i + arraySize2Send, stringLength)
)
) // Commit buffer
session.setParameterShort(
10006,
1.toShort()
) // Notify send array completed and generate filter in native side
}
}
session.setParameterShort(
1210,
stringEqEnabled.toShort()
) // String equalizer switch
if (reverbEnabled == 1 && updateMajor) session.setParameterShort(
128, preferences.getString("dsp.headphone.preset", "0")!!
.toShort()
)
session.setParameterShort(session.jamesDSP, 1203, reverbEnabled.toShort()) // Reverb switch
if (stereoWideEnabled == 1 && updateMajor) session.setParameterShort(
137, preferences.getString("dsp.stereowide.mode", "60")!!
.toShort()
)
session.setParameterShort(
1204,
stereoWideEnabled.toShort()
) // Stereo widener switch
if (bs2bEnabled == 1 && updateMajor) session.setParameterShort(
188, preferences.getString("dsp.bs2b.mode", "0")!!
.toShort()
)
session.setParameterShort(1208, bs2bEnabled.toShort()) // BS2B switch
if (analogModelEnabled == 1 && updateMajor) session.setParameterShort(
150,
(java.lang.Float.valueOf(
preferences.getString(
"dsp.analogmodelling.tubedrive",
"2"
)
) * 1000).toShort()
)
session.setParameterShort(
1206,
analogModelEnabled.toShort()
) // Analog modelling switch
updateNow = true
if (viperddcEnabled == 1 && updateMajor) {
val ddcFilePath = preferences.getString("dsp.ddc.files", "")
if ((oldVDCName == ddcFilePath) && numberOfParameterCommitted != 0) updateNow = false
if (updateNow) {
oldVDCName = ddcFilePath
val contentBuilder = StringBuilder()
try {
BufferedReader(FileReader(ddcFilePath)).use { br ->
var sCurrentLine: String?
while ((br.readLine()
.also { sCurrentLine = it }) != null
) contentBuilder.append(sCurrentLine).append("\n")
}
} catch (e: IOException) {
e.printStackTrace()
}
val arraySize2Send = 256
ddcString = contentBuilder.toString()
if (ddcString != null && !ddcString!!.isEmpty()) {
val stringLength = ddcString!!.length
val numTime2Send =
Math.ceil(stringLength.toDouble() / arraySize2Send)
.toInt() // Number of times that have to send
session.setParameterIntArray(
8888,
intArrayOf(numTime2Send, arraySize2Send)
) // Send buffer info for module to allocate memory
for (i in 0 until numTime2Send) session.setParameterCharArray(
12001,
ddcString!!.substring(
arraySize2Send * i,
Math.min(arraySize2Send * i + arraySize2Send, stringLength)
)
) // Commit buffer
session.setParameterShort(
10009,
1.toShort()
) // Notify send array completed and generate filter in native side
}
}
} else oldVDCName = ""
session.setParameterShort(1212, viperddcEnabled.toShort()) // VDC switch
updateNow = true
if (liveProgEnabled == 1 && updateMajor) {
val eelFilePath = preferences.getString("dsp.liveprog.files", "")
if ((oldEELProgramName == eelFilePath) && numberOfParameterCommitted != 0) updateNow = false
if (updateNow) {
oldEELProgramName = eelFilePath
val contentBuilder = StringBuilder()
try {
BufferedReader(FileReader(eelFilePath)).use { br ->
var sCurrentLine: String?
while ((br.readLine()
.also { sCurrentLine = it }) != null
) contentBuilder.append(sCurrentLine).append("\n")
}
} catch (e: IOException) {
e.printStackTrace()
}
val arraySize2Send = 256
eelProgString = contentBuilder.toString()
if (eelProgString != null && !eelProgString!!.isEmpty()) {
val stringLength = eelProgString!!.length
val numTime2Send =
ceil(stringLength.toDouble() / arraySize2Send)
.toInt() // Number of times that have to send
session.setParameterIntArray(
8888,
intArrayOf(numTime2Send, arraySize2Send)
) // Send buffer info for module to allocate memory
for (i in 0 until numTime2Send) session.setParameterCharArray(
12001,
eelProgString!!.substring(
arraySize2Send * i,
Math.min(arraySize2Send * i + arraySize2Send, stringLength)
)
) // Commit buffer
session.setParameterShort(
10010,
1.toShort()
) // Notify send array completed and generate filter in native side
}
}
} else oldEELProgramName = ""
session.setParameterShort(
1213,
liveProgEnabled.toShort()
) // LiveProg switch
updateNow = true
if (convolverEnabled == 1 && updateMajor) {
val mConvIRFilePath = preferences.getString("dsp.convolver.files", "")
val convMode = Integer.valueOf(preferences.getString("dsp.convolver.mode", "0"))
val advConv = preferences.getString("dsp.convolver.advimp", "-80;-100;23;12;17;28")!!
.split(";").toTypedArray()
val advSetting = IntArray(6)
advSetting[0] = -100
advSetting[1] = advSetting[0]
if (advConv.size == 6) {
for (i in advConv.indices) advSetting[i] = Integer.valueOf(advConv[i])
//Log.e("Dsp", "Advance settings: " + Arrays.toString(advSetting));// Debug
}
if ((oldImpulseName == mConvIRFilePath) && (numberOfParameterCommitted != 0) && (oldConvMode == convMode) && Arrays.equals(
oldImpSet,
advSetting
)
) updateNow = false
if (updateNow) {
oldImpulseName = mConvIRFilePath
oldConvMode = convMode
oldImpSet = advSetting.clone()
session.setParameterShort(1205, 0.toShort())
val mConvIRFileName: String = mConvIRFilePath.replace(DSPManager.impulseResponsePath, "")
val impinfo = IntArray(2)
//Log.e("Dsp", "Conv mode: " + convMode);// Debug
val impulseResponse: FloatArray = JdspImpResToolbox.ReadImpulseResponseToFloat(
mConvIRFilePath,
dspModuleSamplingRate,
impinfo,
convMode,
advSetting
)
//Log.e("Dsp", "Channels: " + impinfo[0] + ", frameCount: " + impinfo[1]);// Debug
if (impinfo[1] == 0) {
Toast.makeText(this@HeadsetService, R.string.impfilefault, Toast.LENGTH_SHORT).show()
} else {
val arraySize2Send = 4096
impinfo[1] = impulseResponse.size / impinfo[0]
val impulseCutted = impulseResponse.size
val sendArray = FloatArray(arraySize2Send)
val numTime2Send =
Math.ceil(impulseCutted.toDouble() / arraySize2Send)
.toInt() // Send number of times that have to send
val sendBufInfo = intArrayOf(impulseCutted, impinfo[0], 0, numTime2Send)
session.setParameterIntArray(
9999,
sendBufInfo
) // Send buffer info for module to allocate memory
val finalArray =
FloatArray(numTime2Send * arraySize2Send) // Fill final array with zero padding
System.arraycopy(impulseResponse, 0, finalArray, 0, impulseCutted)
for (i in 0 until numTime2Send) {
System.arraycopy(finalArray, arraySize2Send * i, sendArray, 0, arraySize2Send)
session.setParameterFloatArray(12000, sendArray) // Commit buffer
}
session.setParameterShort(
10004,
1.toShort()
)
}
} else {
oldImpulseName = ""
oldConvMode = 0
Arrays.fill(oldImpSet, 0)
}
session.setParameterShort(
session.jamesDSP,
1205,
convolverEnabled.toShort()
) // Convolver switch
}
}
}
}

private fun IntToByte(input: IntArray): ByteArray {
var int_index: Int
var byte_index: Int
val iterations = input.size
val buffer = ByteArray(input.size * 4)
byte_index = 0
int_index = byte_index
while (int_index != iterations) {
buffer[byte_index] = (input[int_index] and 0x00FF).toByte()
buffer[byte_index + 1] = (input[int_index] and 0xFF00 shr 8).toByte()
buffer[byte_index + 2] = (input[int_index] and 0xFF0000 shr 16).toByte()
buffer[byte_index + 3] = (input[int_index] and -0x1000000 shr 24).toByte()
++int_index
byte_index += 4
}
return buffer
}

private fun byteArrayToInt(encodedValue: ByteArray): Int {
var value: Int = encodedValue[3] shl 24
value = value or (encodedValue[2] and 0xFF shl 16)
value = value or (encodedValue[1] and 0xFF shl 8)
value = value or (encodedValue[0] and 0xFF)
return value
}

private fun concatArrays(vararg arrays: ByteArray): ByteArray {
var len = 0
for (a: ByteArray in arrays) len += a.size
val b = ByteArray(len)
var offs = 0
for (a: ByteArray in arrays) {
System.arraycopy(a, 0, b, offs, a.size)
offs += a.size
}
return b
}

private fun mergeFloatArray(vararg arrays: FloatArray): FloatArray {
var size = 0
for (a: FloatArray in arrays) size += a.size
val res = FloatArray(size)
var destPos = 0
for (i in arrays.indices) {
if (i > 0) destPos += arrays[i - 1].size
val length: Int = arrays[i].size
System.arraycopy(arrays[i], 0, res, destPos, length)
}
return res
}
*/