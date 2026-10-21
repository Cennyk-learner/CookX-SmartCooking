package com.smartcooking.app.offline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.smartcooking.app.core.*
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import java.util.UUID

/** Explicit opt-in only: makes real provider requests; never runs in ordinary tests or CI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DemoGatewayLiveTest {
    @Test fun realShortSpeechTranscriptionAndNavigationCommands() {
        assumeTrue("Set COOKX_LIVE_VERIFY=1 explicitly to call the provider", System.getenv("COOKX_LIVE_VERIFY") == "1")
        val props = Properties().apply { File("../demo-private.properties").inputStream().use { load(it) } }
        val gateway = DemoGateway(KeyValueStore(RuntimeEnvironment.getApplication(), "asr-" + UUID.randomUUID()),
            DemoAccess(props.getProperty("apiKey"), props.getProperty("compatibleBaseUrl")))
        for ((spoken, expected) in listOf("下一步" to "next", "返回上一步" to "previous", "不要下一步" to null)) {
            val audio = gateway.tts(spoken)
            val mime = if (audio.take(4).toByteArray().toString(Charsets.US_ASCII) == "RIFF") "audio/wav" else "audio/mpeg"
            val recognized = gateway.transcribe(audio,mime)
            val parsed = com.smartcooking.app.feature.cooking.VoiceCommands.parse(recognized,null)
            if (expected == null) assertFalse(recognized,parsed.confirmed) else {
                assertTrue(recognized,parsed.confirmed); assertEquals(expected,parsed.matches.single())
            }
            println("LIVE_ASR_OK expected=$expected transcript=$recognized (synthetic speech, not a phone microphone test)")
        }
    }
    @Test fun realVisionAndSpeechUsingPrivateConfig() {
        assumeTrue("Set COOKX_LIVE_VERIFY=1 explicitly to call the provider", System.getenv("COOKX_LIVE_VERIFY") == "1")
        val props = Properties().apply { File("../demo-private.properties").inputStream().use { load(it) } }
        val bundle = Properties().apply { File("../.gradle/cookx-demo-bundle.properties").inputStream().use { load(it) } }
        val access = BundledDemoAccess.decode(bundle.getProperty("payload"), bundle.getProperty("secret"), props.getProperty("compatibleBaseUrl"))!!
        assertTrue("Encrypted bundle must match the private build input", access.apiKey == props.getProperty("apiKey"))
        val gateway = DemoGateway(KeyValueStore(RuntimeEnvironment.getApplication(), "live-" + UUID.randomUUID()),
            access)
        assertTrue(gateway.configured); assertTrue(gateway.usingBuiltIn)
        val bitmap = BitmapFactory.decodeFile("src/main/res/drawable-nodpi/ing_tomato.webp")
        val bytes = ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output); output.toByteArray() }
        bitmap.recycle()
        val photo = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("image", "tomato.jpg", bytes.toRequestBody("image/jpeg".toMediaType())).build()
        val vision = gateway.recognize(photo)
        assertEquals("online", vision.str("source"))
        assertTrue("Detected names: " + vision.objects("detected").map { it.str("name") }, vision.objects("detected").any { it.str("name")?.let { name -> name.contains("番茄") || name.contains("西红柿") } == true })
        println("LIVE_VISION_OK bundled tomato illustration identified; not a camera test")

        val audio = gateway.tts("欢迎使用鲜厨智享")
        assertTrue(audio.size > 1024)
        println("LIVE_TTS_OK bytes=" + audio.size)
    }
}
