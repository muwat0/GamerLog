package dev.gamerlog.app.core.backloggd.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidBackloggdCredentialStoreTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun roundTripAndClear() {
        val store = newStore()
        val credentials = BackloggdCredentials("gamer_user-1", "session=secret-cookie")
        try {
            assertNull(store.load())
            store.save(credentials)
            assertEquals(credentials, store.load())
            store.clear()
            assertNull(store.load())
        } finally {
            store.clear()
        }
    }

    @Test
    fun savedFileContainsCiphertextNotCredentialPlaintext() {
        val store = newStore()
        val credentials = BackloggdCredentials("plain_user", "session=very-secret-cookie")
        try {
            store.save(credentials)
            val stored = File(context.noBackupFilesDir, storageNameForLastStore())
                .readBytes()
                .toString(Charsets.ISO_8859_1)
            assertFalse(stored.contains(credentials.username))
            assertFalse(stored.contains(credentials.cookieHeader))
        } finally {
            store.clear()
        }
    }

    @Test
    fun tamperingRejectsLoadAsVisibleCredentialLoss() {
        val store = newStore()
        try {
            store.save(BackloggdCredentials("valid_user", "session=valid-cookie"))
            val file = File(context.noBackupFilesDir, storageNameForLastStore())
            val bytes = file.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
            file.writeBytes(bytes)

            assertThrows(BackloggdCredentialStoreException::class.java) {
                store.load()
            }
        } finally {
            store.clear()
        }
    }

    private var lastStorageName: String = ""

    private fun newStore(): AndroidBackloggdCredentialStore {
        val id = UUID.randomUUID().toString().replace("-", "")
        lastStorageName = "backloggd_credentials_test_$id.enc"
        return AndroidBackloggdCredentialStore(
            context = context,
            storageName = lastStorageName,
            keyAlias = "dev.gamerlog.test.backloggd.$id"
        )
    }

    private fun storageNameForLastStore(): String = lastStorageName
}
