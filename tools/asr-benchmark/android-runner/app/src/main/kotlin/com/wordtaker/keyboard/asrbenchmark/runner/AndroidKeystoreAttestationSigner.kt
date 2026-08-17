package com.wordtaker.keyboard.asrbenchmark.runner

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.wordtaker.keyboard.asrbenchmark.core.AttestationSignature
import com.wordtaker.keyboard.asrbenchmark.core.AttestationSignatureProvider
import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException
import com.wordtaker.keyboard.asrbenchmark.core.Hashing
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

enum class RequiredKeystoreSecurity {
    DEVELOPMENT,
    HARDWARE_TEE_OR_STRONGBOX,
    STRONGBOX,
}

data class AndroidKeystoreSignatureEvidence(
    val signatureDer: ByteArray,
    val certificateChainDer: List<ByteArray>,
    val localSecurityLevelClaim: String,
)

class AndroidKeystoreAttestationSigner {
    fun sign(
        runId: String,
        attestationChallengeSha256: String,
        message: ByteArray,
        requiredSecurity: RequiredKeystoreSecurity,
    ): AndroidKeystoreSignatureEvidence {
        if (!Regex("^run_[0-9a-f]{12}$").matches(runId)) {
            throw BenchmarkContractException("Keystore run_id is invalid")
        }
        Hashing.requireSha256(
            attestationChallengeSha256,
            "Keystore attestation challenge",
        )
        if (message.isEmpty()) {
            throw BenchmarkContractException("Keystore signed message is empty")
        }
        val keyAlias = "kittyecho_asr_${runId.removePrefix("run_")}_" +
            attestationChallengeSha256.take(16)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(keyAlias)) {
            throw BenchmarkContractException(
                "Keystore attestation alias already exists; refusing replay or overwrite",
            )
        }
        val builder = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_SIGN,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(attestationChallengeSha256.hexToByteArray())
            .setUserAuthenticationRequired(false)
        if (requiredSecurity == RequiredKeystoreSecurity.STRONGBOX) {
            if (Build.VERSION.SDK_INT < 28) {
                throw BenchmarkContractException("StrongBox requires Android API 28+")
            }
            builder.setIsStrongBoxBacked(true)
        }
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            "AndroidKeyStore",
        )
        try {
            generator.initialize(builder.build())
            generator.generateKeyPair()
        } catch (error: StrongBoxUnavailableException) {
            throw BenchmarkContractException(
                "StrongBox was required but is unavailable; no downgrade is allowed",
            )
        } catch (error: Exception) {
            throw BenchmarkContractException(
                "Android hardware key attestation generation failed: " +
                    error.javaClass.simpleName,
            )
        }
        val privateKey = keyStore.getKey(keyAlias, null) as? PrivateKey
            ?: throw BenchmarkContractException("generated Keystore key is unavailable")
        val keyInfo = KeyFactory.getInstance(
            privateKey.algorithm,
            "AndroidKeyStore",
        ).getKeySpec(privateKey, KeyInfo::class.java)
        val localSecurityLevel = localSecurityLevel(keyInfo)
        when (requiredSecurity) {
            RequiredKeystoreSecurity.DEVELOPMENT -> Unit
            RequiredKeystoreSecurity.HARDWARE_TEE_OR_STRONGBOX -> {
                if (localSecurityLevel !in setOf("trusted_environment", "strongbox")) {
                    throw BenchmarkContractException(
                        "hardware Keystore was required but generated a software key",
                    )
                }
            }
            RequiredKeystoreSecurity.STRONGBOX -> {
                if (localSecurityLevel != "strongbox") {
                    throw BenchmarkContractException(
                        "StrongBox was required but the generated key is not StrongBox",
                    )
                }
            }
        }
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(message)
            sign()
        }
        val chain = keyStore.getCertificateChain(keyAlias)
            ?.map { it.encoded }
            .orEmpty()
        if (chain.isEmpty()) {
            throw BenchmarkContractException("Keystore certificate chain is unavailable")
        }
        return AndroidKeystoreSignatureEvidence(
            signatureDer = signature,
            certificateChainDer = chain,
            localSecurityLevelClaim = localSecurityLevel,
        )
    }

    private fun localSecurityLevel(keyInfo: KeyInfo): String {
        return if (Build.VERSION.SDK_INT >= 31) {
            when (keyInfo.securityLevel) {
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ->
                    "trusted_environment"
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "strongbox"
                else -> "unknown"
            }
        } else {
            @Suppress("DEPRECATION")
            if (keyInfo.isInsideSecureHardware) {
                "trusted_environment"
            } else {
                "software"
            }
        }
    }

    private fun String.hexToByteArray(): ByteArray {
        if (length % 2 != 0) {
            throw BenchmarkContractException("hex value has odd length")
        }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

class AndroidKeystoreSignatureProvider(
    private val runId: String,
    private val requiredSecurity: RequiredKeystoreSecurity,
    private val delegate: AndroidKeystoreAttestationSigner =
        AndroidKeystoreAttestationSigner(),
) : AttestationSignatureProvider {
    override fun sign(
        attestationChallengeSha256: String,
        message: ByteArray,
    ): AttestationSignature {
        val evidence = delegate.sign(
            runId = runId,
            attestationChallengeSha256 = attestationChallengeSha256,
            message = message,
            requiredSecurity = requiredSecurity,
        )
        return AttestationSignature(
            signatureDer = evidence.signatureDer,
            certificateChainDer = evidence.certificateChainDer,
            localSecurityLevelClaim = evidence.localSecurityLevelClaim,
        )
    }
}
