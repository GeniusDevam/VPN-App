package io.github.geniusdevam.vpnapp.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Everything the app stores (server list, settings, history, reputation) goes through here:
 * AES-256-GCM with a non-exportable key held in the Android Keystore (hardware-backed where available).
 * Each file is bound to its name as additional authenticated data, so files cannot be swapped.
 */
object SecureVault {
    private const val ALIAS = "vpnapp_vault_v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun file(ctx: Context, name: String): File {
        val dir = File(ctx.filesDir, "vault")
        dir.mkdirs()
        return File(dir, "$name.bin")
    }

    @Synchronized
    fun write(ctx: Context, name: String, data: ByteArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        c.updateAAD(name.toByteArray())
        val ct = c.doFinal(data)
        val iv = c.iv
        val out = ByteArray(1 + iv.size + ct.size)
        out[0] = iv.size.toByte()
        System.arraycopy(iv, 0, out, 1, iv.size)
        System.arraycopy(ct, 0, out, 1 + iv.size, ct.size)
        val f = file(ctx, name)
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(out)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    /** Null if the file is missing, corrupt, or the key is gone (e.g. after a device reset). */
    @Synchronized
    fun read(ctx: Context, name: String): ByteArray? {
        return try {
            val f = file(ctx, name)
            if (!f.exists()) return null
            val raw = f.readBytes()
            val ivLen = raw[0].toInt()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 1, ivLen))
            c.updateAAD(name.toByteArray())
            c.doFinal(raw, 1 + ivLen, raw.size - 1 - ivLen)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun deleteAll(ctx: Context) {
        File(ctx.filesDir, "vault").deleteRecursively()
    }
}
