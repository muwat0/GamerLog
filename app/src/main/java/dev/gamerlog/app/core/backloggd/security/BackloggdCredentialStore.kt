package dev.gamerlog.app.core.backloggd.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials are intentionally excluded from the default data-class string representation. */
data class BackloggdCredentials(val username: String, val cookieHeader: String) {
    override fun toString(): String = "BackloggdCredentials(username=$username, cookieHeader=<redacted>)"
}

interface BackloggdCredentialStore {
    fun load(): BackloggdCredentials?
    fun save(credentials: BackloggdCredentials)
    fun clear()
}

/** A sanitized failure indicating that credentials could not safely be read or written. */
class BackloggdCredentialStoreException : Exception("Backloggd credentials are unavailable")

class AndroidBackloggdCredentialStore internal constructor(
    context: Context,
    storageName: String,
    keyAlias: String
) : BackloggdCredentialStore {
    constructor(context: Context) : this(
        context = context.applicationContext,
        storageName = DEFAULT_STORAGE_NAME,
        keyAlias = DEFAULT_KEY_ALIAS
    )

    private val file = File(context.noBackupFilesDir, storageName)
    private val atomicFile = AtomicFile(file)
    private val keyAlias = keyAlias
    private val lock = locks.computeIfAbsent(file.absolutePath) { Any() }

    override fun load(): BackloggdCredentials? = synchronized(lock) {
        if (!hasStoredFile()) return@synchronized null
        try {
            val blob = atomicFile.openRead().use { it.readBytes() }
            decodeCredentials(decrypt(blob, getExistingKey()))
        } catch (_: Exception) {
            throw BackloggdCredentialStoreException()
        }
    }

    override fun save(credentials: BackloggdCredentials) = synchronized(lock) {
        validate(credentials)
        try {
            val encrypted = encrypt(encodeCredentials(credentials), getOrCreateKey())
            val output = atomicFile.startWrite()
            try {
                output.write(encrypted)
                atomicFile.finishWrite(output)
                setPrivatePermissions(file)
                setPrivatePermissions(File(file.path + ".bak"))
                setPrivatePermissions(File(file.path + ".new"))
            } catch (failure: Exception) {
                atomicFile.failWrite(output)
                throw failure
            }
        } catch (_: Exception) {
            throw BackloggdCredentialStoreException()
        }
    }

    override fun clear() = synchronized(lock) {
        try {
            atomicFile.delete()
            listOf(file, File(file.path + ".bak"), File(file.path + ".new")).forEach {
                if (it.exists() && !it.delete()) throw IllegalStateException()
            }
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
        } catch (_: Exception) {
            throw BackloggdCredentialStoreException()
        }
    }

    private fun hasStoredFile(): Boolean = listOf(
        file,
        File(file.path + ".bak"),
        File(file.path + ".new")
    ).any(File::exists)

    private fun getExistingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        return keyStore.getKey(keyAlias, null) as? SecretKey ?: throw IllegalStateException()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plaintext: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        require(iv.size == GCM_IV_LENGTH)
        val ciphertext = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(HEADER_SIZE + iv.size + ciphertext.size)
            .put(MAGIC)
            .put(FORMAT_VERSION)
            .put(iv.size.toByte())
            .put(iv)
            .put(ciphertext)
            .array()
    }

    private fun decrypt(blob: ByteArray, key: SecretKey): ByteArray {
        require(blob.size >= HEADER_SIZE + GCM_IV_LENGTH + GCM_TAG_LENGTH_BYTES)
        val buffer = ByteBuffer.wrap(blob)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        require(magic.contentEquals(MAGIC))
        require(buffer.get() == FORMAT_VERSION)
        val ivLength = buffer.get().toInt() and 0xff
        require(ivLength == GCM_IV_LENGTH && buffer.remaining() >= ivLength + GCM_TAG_LENGTH_BYTES)
        val iv = ByteArray(ivLength).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun encodeCredentials(credentials: BackloggdCredentials): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeUTF(credentials.username)
                output.writeUTF(credentials.cookieHeader)
            }
            bytes.toByteArray()
        }

    private fun decodeCredentials(plaintext: ByteArray): BackloggdCredentials =
        DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
            val credentials = BackloggdCredentials(input.readUTF(), input.readUTF())
            require(input.available() == 0)
            validate(credentials)
            credentials
        }

    private fun validate(credentials: BackloggdCredentials) {
        require(USERNAME_PATTERN.matches(credentials.username)) { "Invalid Backloggd username" }
        val cookie = credentials.cookieHeader
        require(cookie.isNotBlank() && cookie.length <= MAX_COOKIE_LENGTH) { "Invalid cookie header" }
        require(cookie.none { it == '\r' || it == '\n' || it.isISOControl() }) { "Invalid cookie header" }
    }

    private fun setPrivatePermissions(target: File) {
        if (target.exists()) Os.chmod(target.path, PRIVATE_FILE_MODE)
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val DEFAULT_STORAGE_NAME = "backloggd_credentials.enc"
        private const val DEFAULT_KEY_ALIAS = "dev.gamerlog.app.backloggd.credentials.v1"
        private const val FORMAT_VERSION: Byte = 1
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val GCM_TAG_LENGTH_BYTES = GCM_TAG_LENGTH_BITS / 8
        private const val MAX_COOKIE_LENGTH = 16_384
        private const val PRIVATE_FILE_MODE = 0x180 // 0600
        private val MAGIC = byteArrayOf(0x47, 0x4c, 0x42, 0x43) // GLBC
        private val HEADER_SIZE = MAGIC.size + 2
        private val USERNAME_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        private val locks = ConcurrentHashMap<String, Any>()
    }
}
