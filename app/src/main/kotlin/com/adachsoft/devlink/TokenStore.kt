package com.adachsoft.devlink

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.security.SecureRandom

class TokenStore(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun getOrCreateToken(): String = synchronized(preferences) {
        val token = preferences.getString(TOKEN_KEY, null)
            ?.takeIf(TOKEN_PATTERN::matches)
            ?: generateToken().also { generatedToken ->
                check(preferences.edit().putString(TOKEN_KEY, generatedToken).commit()) {
                    "Unable to persist the bearer token."
                }
            }
        writeTokenFile(token)
        token
    }

    private fun writeTokenFile(token: String) {
        val directory = applicationContext.getExternalFilesDir(null) ?: return
        try {
            File(directory, TOKEN_FILE_NAME).writeText(token, Charsets.US_ASCII)
        } catch (exception: IOException) {
            Log.e(TAG, "Unable to write the bearer token file.", exception)
        } catch (exception: SecurityException) {
            Log.e(TAG, "Unable to write the bearer token file.", exception)
        }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTE_COUNT)
        secureRandom.nextBytes(bytes)
        val digits = "0123456789abcdef"
        val characters = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            characters[index * 2] = digits[value ushr 4]
            characters[index * 2 + 1] = digits[value and 0x0f]
        }
        return String(characters)
    }

    companion object {
        private const val PREFERENCES_NAME = "devlink"
        private const val TOKEN_KEY = "bearer_token"
        private const val TOKEN_FILE_NAME = "token.txt"
        private const val TOKEN_BYTE_COUNT = 32
        private const val TAG = "DevlinkTokenStore"

        private val secureRandom = SecureRandom()
        private val TOKEN_PATTERN = Regex("[0-9a-f]{64}")
    }
}
