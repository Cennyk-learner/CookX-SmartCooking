package com.smartcooking.app.offline

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Conceals plaintext strings in the APK; a device-side bundle cannot prevent reverse engineering. */
internal object BundledDemoAccess {
    fun decode(payload: String, secret: String, baseUrl: String): DemoAccess? =
        decodeKey(payload, secret, baseUrl)?.let { DemoAccess(it, baseUrl) }

    fun decodeKey(payload: String, secret: String, baseUrl: String): String? {
        if (payload.isEmpty() && secret.isEmpty()) return null
        val bytes = Base64.decode(payload, Base64.NO_WRAP)
        require(bytes.size > 28) { "内置服务配置不完整" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(Base64.decode(secret, Base64.NO_WRAP), "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(baseUrl.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
}
