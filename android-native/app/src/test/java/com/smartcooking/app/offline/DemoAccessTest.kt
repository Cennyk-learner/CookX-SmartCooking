package com.smartcooking.app.offline

import com.smartcooking.app.core.KeyValueStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DemoAccessTest {
    private val builtIn = DemoAccess("test-only-builtin-credential", "https://ws-example.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/")
    private val store = KeyValueStore(RuntimeEnvironment.getApplication(), "access-" + UUID.randomUUID())
    private val state = DemoAccessState(store, builtIn)
    // Encryption is a separate Android Keystore responsibility; this fake checks persistence and selection only.
    private fun encrypt(value: String) = "cipher:" + value.reversed()
    private fun decrypt(value: String) = value.removePrefix("cipher:").reversed()

    @Test fun freshInstallUsesMatchingBuiltInEndpointWithoutWritingPlaintextKey() {
        assertTrue(state.configured); assertTrue(state.usingBuiltIn)
        assertSame(builtIn, state.resolve(::decrypt))
        assertTrue(store.keys("cookx:").isEmpty())
        assertEquals("https://ws-example.cn-beijing.maas.aliyuncs.com/api/v1", builtIn.nativeBaseUrl)
        assertFalse(builtIn.toString().contains(builtIn.apiKey))
    }
    @Test fun savedOverrideKeepsItsOwnEndpointAcrossRestartsAndCanRestoreBuiltIn() {
        state.save(DemoAccess("test-only-custom-credential", DemoAccess.SHARED_BASE), ::encrypt)
        val restarted = DemoAccessState(store, builtIn)
        assertFalse(restarted.usingBuiltIn)
        assertEquals(DemoAccess.SHARED_BASE, restarted.resolve(::decrypt).compatibleBaseUrl)
        assertEquals("test-only-custom-credential", restarted.resolve(::decrypt).apiKey)
        assertFalse(store.get(DemoAccessState.SAVED)!!.contains("test-only-custom-credential"))
        restarted.restoreBuiltIn(); assertTrue(restarted.usingBuiltIn)
        assertEquals(builtIn.apiKey, restarted.resolve(::decrypt).apiKey)
    }
    @Test fun disablingSurvivesRestartAndDoesNotSilentlyFallBackToBundledKey() {
        state.save(DemoAccess("test-only-custom-credential", DemoAccess.SHARED_BASE), ::encrypt)
        state.disable()
        val restarted = DemoAccessState(store, builtIn)
        assertFalse(restarted.configured)
        assertTrue(runCatching { restarted.resolve(::decrypt) }.isFailure)
        assertNull(store.get(DemoAccessState.SAVED))
        restarted.restoreBuiltIn(); assertTrue(restarted.configured)
    }
    @Test fun legacySavedKeyRetainsSharedEndpointInsteadOfUsingNewWorkspace() {
        store.putSync(DemoAccessState.LEGACY, encrypt("test-only-legacy-credential"))
        assertEquals(DemoAccess.SHARED_BASE, state.resolve(::decrypt).compatibleBaseUrl)
        assertEquals("test-only-legacy-credential", state.resolve(::decrypt).apiKey)
        assertFalse(state.usingBuiltIn)
    }
    @Test fun corruptOverrideNeverFallsThroughToAnotherAccount() {
        store.putSync(DemoAccessState.SAVED, encrypt("invalid JSON"))
        assertTrue(runCatching { state.resolve(::decrypt) }.isFailure)
    }
    @Test fun endpointsRejectCredentialLeaksAndMalformedRoutes() {
        for (url in listOf("http://dashscope.aliyuncs.com/compatible-mode/v1", "https://evil.example/compatible-mode/v1", "https://dashscope.aliyuncs.com.evil.example/compatible-mode/v1", "https://user:password@dashscope.aliyuncs.com/compatible-mode/v1", "https://dashscope.aliyuncs.com:444/compatible-mode/v1", "https://dashscope.aliyuncs.com/compatible-mode/v1?key=x", "https://dashscope.aliyuncs.com/api/v1")) {
            assertTrue(url, runCatching { DemoAccess("test-only-credential", url) }.isFailure)
        }
    }
    @Test fun publicBuildRequiresConfigurationAndCanEnableAfterDisable() {
        val public = DemoAccessState(store, null)
        assertFalse(public.configured); assertFalse(public.hasBuiltIn)
        public.disable(); public.save(DemoAccess("test-only-custom-credential", DemoAccess.SHARED_BASE), ::encrypt)
        assertTrue(public.configured)
        assertTrue(runCatching { public.restoreBuiltIn() }.isFailure)
    }
    @Test fun encryptedBundleDecodesAndRejectsTamperedEndpoint() {
        val secret = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val nonce = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(javax.crypto.Cipher.ENCRYPT_MODE, secret, javax.crypto.spec.GCMParameterSpec(128, nonce))
            updateAAD(builtIn.compatibleBaseUrl.toByteArray(Charsets.UTF_8))
        }
        val payload = android.util.Base64.encodeToString(nonce + cipher.doFinal(builtIn.apiKey.toByteArray(Charsets.UTF_8)), android.util.Base64.NO_WRAP)
        val secretText = android.util.Base64.encodeToString(secret.encoded, android.util.Base64.NO_WRAP)
        assertEquals(builtIn.apiKey, BundledDemoAccess.decode(payload, secretText, builtIn.compatibleBaseUrl)!!.apiKey)
        assertTrue(runCatching { BundledDemoAccess.decode(payload, secretText, DemoAccess.SHARED_BASE) }.isFailure)
        assertNull(BundledDemoAccess.decode("", "", DemoAccess.SHARED_BASE))
    }
}
