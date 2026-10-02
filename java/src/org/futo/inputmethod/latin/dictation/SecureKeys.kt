package org.futo.inputmethod.latin.dictation

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.security.KeyStore
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys protected by an Android Keystore AES-GCM key. Plaintext never leaves process memory,
 * is never logged, and is never written to DataStore/SharedPreferences.
 *
 * Storage: `filesDir/dictation/keys.enc` = [12-byte IV][GCM ciphertext of a .properties blob].
 * Import: `getExternalFilesDir(null)/keys.properties` (pushable with adb, no runtime permission
 * needed), read once, encrypted, then deleted.
 */
object SecureKeys {
    private const val TAG = "SecureKeys"
    private const val ALIAS = "dictation_api_keys_v1"
    private const val IMPORT_FILE = "keys.properties"
    const val KEY_SPEECHMATICS = "speechmatics"
    const val KEY_ANTHROPIC = "anthropic"
    private val KNOWN = listOf(KEY_SPEECHMATICS, KEY_ANTHROPIC)

    @Volatile private var cache: Map<String, String>? = null

    private fun encFile(context: Context) = File(File(context.filesDir, "dictation"), "keys.enc")
    fun importFile(context: Context): File = File(context.getExternalFilesDir(null), IMPORT_FILE)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    @Synchronized
    private fun load(context: Context): Map<String, String> {
        cache?.let { return it }
        val f = encFile(context)
        if (!f.exists()) return emptyMap<String, String>().also { cache = it }
        return try {
            val bytes = f.readBytes()
            val iv = bytes.copyOfRange(0, 12)
            val ct = bytes.copyOfRange(12, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            val props = Properties().apply { load(cipher.doFinal(ct).inputStream().reader()) }
            props.entries.associate { it.key.toString() to it.value.toString() }.also { cache = it }
        } catch (e: Exception) {
            // Credential-encrypted storage or keystore unavailable (direct boot), or corrupt file.
            Log.e(TAG, "Could not load keys: ${e.javaClass.simpleName}")
            emptyMap()
        }
    }

    @Synchronized
    private fun save(context: Context, values: Map<String, String>) {
        val props = Properties().apply { values.forEach { (k, v) -> setProperty(k, v) } }
        val plain = java.io.StringWriter().also { props.store(it, null) }.toString().toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val out = cipher.iv + cipher.doFinal(plain)
        val f = encFile(context)
        f.parentFile?.mkdirs()
        f.writeBytes(out)
        cache = values
    }

    fun get(context: Context, name: String): String? = load(context)[name]?.takeIf { it.isNotBlank() }

    /** Names of keys present (never values). */
    fun presentKeys(context: Context): List<String> = KNOWN.filter { get(context, it) != null }

    sealed class ImportResult {
        data class Ok(val imported: List<String>) : ImportResult()
        data class Failed(val reason: String) : ImportResult()
    }

    /** Reads the import file, merges into the store, deletes the file. Values are never logged. */
    @Synchronized
    fun importFromFile(context: Context): ImportResult {
        val f = importFile(context)
        if (!f.exists()) return ImportResult.Failed("no ${f.name} in ${f.parentFile}")
        return try {
            val props = Properties().apply { f.inputStream().use { load(it) } }
            val incoming = KNOWN.mapNotNull { k ->
                props.getProperty(k)?.trim()?.takeIf { it.isNotEmpty() }?.let { k to it }
            }.toMap()
            if (incoming.isEmpty()) {
                ImportResult.Failed("no recognized keys (expected: ${KNOWN.joinToString()})")
            } else {
                save(context, load(context) + incoming)
                ImportResult.Ok(incoming.keys.toList())
            }
        } catch (e: Exception) {
            ImportResult.Failed(e.javaClass.simpleName)
        } finally {
            try { f.delete() } catch (_: Exception) {}
        }
    }

    @Synchronized
    fun clear(context: Context) {
        encFile(context).delete()
        cache = emptyMap()
    }
}
