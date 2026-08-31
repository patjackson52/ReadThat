@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.readthat.client

import cnames.structs.__CFData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Foundation.NSUserDefaults
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy

/** Session tokens live in Keychain and never enter preferences or Room. */
class AppleKeychainSessionStore(
    private val service: String = "dev.readthat.session",
) : SecureSessionStore {
    private val json = Json { encodeDefaults = true }
    private val defaults = NSUserDefaults.standardUserDefaults

    override suspend fun readSession(): StoredSession? = keychainRead(SESSION_ACCOUNT)?.let { bytes ->
        runCatching { json.decodeFromString<StoredSession>(bytes.decodeToString()) }
            .getOrElse { clearSession(); null }
    }

    override suspend fun writeSession(session: StoredSession) {
        keychainWrite(SESSION_ACCOUNT, json.encodeToString(session).encodeToByteArray())
    }

    override suspend fun clearSession() { keychainDelete(SESSION_ACCOUNT) }
    override suspend fun readBookmark(): String? = defaults.stringForKey(BOOKMARK_KEY)
    override suspend fun writeBookmark(value: String) { defaults.setObject(value, BOOKMARK_KEY) }
    override suspend fun clearBookmark() { defaults.removeObjectForKey(BOOKMARK_KEY) }

    private fun keychainRead(account: String): ByteArray? = withQuery(account) { query ->
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            if (status == errSecItemNotFound) return@memScoped null
            check(status == errSecSuccess) { "Keychain read failed ($status)" }
            val data = result.value?.reinterpret<__CFData>() ?: return@memScoped null
            try {
                val size = CFDataGetLength(data).toInt()
                val source = CFDataGetBytePtr(data) ?: return@memScoped ByteArray(0)
                ByteArray(size).also { output ->
                    output.usePinned { pinned -> memcpy(pinned.addressOf(0), source, size.convert()) }
                }
            } finally {
                result.value?.let(::CFRelease)
            }
        }
    }

    private fun keychainWrite(account: String, bytes: ByteArray) = withQuery(account) { query ->
        bytes.usePinned { pinned ->
            val data = CFDataCreate(kCFAllocatorDefault, pinned.addressOf(0).reinterpret<UByteVar>(), bytes.size.convert())
                ?: error("Unable to allocate Keychain value")
            try {
                val update = CFDictionaryCreateMutable(kCFAllocatorDefault, 0, null, null)
                    ?: error("Unable to allocate Keychain update")
                try {
                    CFDictionarySetValue(update, kSecValueData, data)
                    val updated = SecItemUpdate(query, update)
                    if (updated == errSecItemNotFound) {
                        CFDictionarySetValue(query, kSecValueData, data)
                        CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                        check(SecItemAdd(query, null) == errSecSuccess) { "Keychain add failed" }
                    } else check(updated == errSecSuccess) { "Keychain update failed ($updated)" }
                } finally {
                    CFRelease(update)
                }
            } finally {
                CFRelease(data)
            }
        }
    }

    private fun keychainDelete(account: String) = withQuery(account) { query ->
        val status = SecItemDelete(query)
        check(status == errSecSuccess || status == errSecItemNotFound) { "Keychain delete failed ($status)" }
    }

    private inline fun <T> withQuery(account: String, block: (CFMutableDictionaryRef) -> T): T {
        val query = CFDictionaryCreateMutable(kCFAllocatorDefault, 0, null, null)
            ?: error("Unable to allocate Keychain query")
        val serviceString = service.cfString()
        val accountString = account.cfString()
        try {
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, serviceString)
            CFDictionarySetValue(query, kSecAttrAccount, accountString)
            return block(query)
        } finally {
            CFRelease(accountString)
            CFRelease(serviceString)
            CFRelease(query)
        }
    }

    private fun String.cfString(): CFStringRef =
        CFStringCreateWithCString(kCFAllocatorDefault, this, kCFStringEncodingUTF8)
            ?: error("Unable to allocate Keychain string")

    private companion object {
        const val SESSION_ACCOUNT = "primary"
        const val BOOKMARK_KEY = "readthat_d1_bookmark"
    }
}
