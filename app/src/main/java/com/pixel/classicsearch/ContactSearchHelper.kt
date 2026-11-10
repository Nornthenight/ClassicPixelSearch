package com.pixel.classicsearch

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

data class ContactCandidate(
    val contactId: Long,
    val lookupKey: String,
    val displayName: String,
    val phoneNumber: String?,
    val photoUri: String?
)

/**
 * High-performance contact search engine and in-memory cache for Pixel OneSearch & QuickSearch Overlay.
 * Supports Russian/English cross-layout (QWERTY <-> JCUKEN), phonetic transliteration,
 * multi-word matching, and phone number lookup.
 */
object ContactSearchHelper {

    const val CACHE_TTL_MS = 60_000L

    @Volatile
    var cachedContacts: List<ContactCandidate> = emptyList()
        internal set

    @Volatile
    private var lastUpdateRealtimeMs: Long = 0L

    @Volatile
    private var isRefreshing: Boolean = false

    private val bgExecutor = Executors.newSingleThreadExecutor()

    fun hasContactsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun getContacts(context: Context): List<ContactCandidate> {
        if (!hasContactsPermission(context)) {
            cachedContacts = emptyList()
            return emptyList()
        }
        val current = cachedContacts
        val now = SystemClock.elapsedRealtime()
        if (current.isNotEmpty()) {
            if (now - lastUpdateRealtimeMs > CACHE_TTL_MS) {
                warmUpAsync(context)
            }
            return current
        }
        return refreshSync(context.applicationContext)
    }

    fun warmUpAsync(context: Context) {
        if (!hasContactsPermission(context)) return
        if (isRefreshing) return
        isRefreshing = true
        val appCtx = context.applicationContext
        bgExecutor.execute {
            try {
                refreshSync(appCtx)
            } finally {
                isRefreshing = false
            }
        }
    }

    @Synchronized
    fun refreshSync(context: Context): List<ContactCandidate> {
        if (!hasContactsPermission(context)) {
            cachedContacts = emptyList()
            return emptyList()
        }
        return try {
            val appCtx = context.applicationContext
            val resolver = appCtx.contentResolver
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
                ContactsContract.CommonDataKinds.Phone.IS_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.STARRED
            )
            val sortOrder = "${ContactsContract.CommonDataKinds.Phone.STARRED} DESC, " +
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} ASC, " +
                    "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC, " +
                    "${ContactsContract.CommonDataKinds.Phone.IS_PRIMARY} DESC"

            val list = mutableListOf<ContactCandidate>()
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val lookupIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY)
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
                val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val photoIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI)

                while (cursor.moveToNext()) {
                    val contactId = if (idIdx >= 0) cursor.getLong(idIdx) else -1L
                    if (contactId <= 0L) continue

                    val lookupKey = (if (lookupIdx >= 0) cursor.getString(lookupIdx) else null)
                        ?: contactId.toString()
                    val rawName = if (nameIdx >= 0) cursor.getString(nameIdx)?.trim() else null
                    val number = if (numberIdx >= 0) cursor.getString(numberIdx)?.trim() else null
                    val photoUri = if (photoIdx >= 0) cursor.getString(photoIdx) else null

                    val displayName = when {
                        !rawName.isNullOrEmpty() -> rawName
                        !number.isNullOrEmpty() -> number
                        else -> continue
                    }

                    list.add(
                        ContactCandidate(
                            contactId = contactId,
                            lookupKey = lookupKey,
                            displayName = displayName,
                            phoneNumber = number,
                            photoUri = photoUri
                        )
                    )
                }
            }
            cachedContacts = list
            lastUpdateRealtimeMs = SystemClock.elapsedRealtime()
            list
        } catch (_: Exception) {
            cachedContacts
        }
    }

    fun invalidateCache() {
        cachedContacts = emptyList()
        lastUpdateRealtimeMs = 0L
    }

    fun findMatchingContacts(
        context: Context,
        rawQuery: String?,
        limit: Int = 4
    ): List<ContactCandidate> {
        if (limit <= 0) return emptyList()
        if (!hasContactsPermission(context)) return emptyList()

        val query = rawQuery?.trim()?.lowercase() ?: return emptyList()
        if (query.isEmpty() || query.startsWith("!") || query.contains('?')) return emptyList()

        val contacts = getContacts(context)
        if (contacts.isEmpty()) return emptyList()

        val queryVariants = AppSearchMatcher.buildQueryVariants(query)
        val queryDigits = query.filter { it.isDigit() }
        val hasDigits = queryDigits.isNotEmpty()
        val isNumericQuery = hasDigits && query.all {
            it.isDigit() || it == '+' || it == ' ' || it == '-' || it == '(' || it == ')'
        }

        val results = mutableListOf<ContactCandidate>()
        val seenContactIds = mutableSetOf<Long>()
        val seenDisplayNames = mutableSetOf<String>()

        fun tryAdd(candidate: ContactCandidate): Boolean {
            val normName = candidate.displayName.trim().lowercase()
            if (seenContactIds.contains(candidate.contactId)) return false
            if (normName.isNotEmpty() && seenDisplayNames.contains(normName)) return false

            seenContactIds.add(candidate.contactId)
            if (normName.isNotEmpty()) {
                seenDisplayNames.add(normName)
            }
            results.add(candidate)
            return results.size >= limit
        }

        if (isNumericQuery) {
            for (candidate in contacts) {
                if (matchPhoneNumberPrefix(candidate.phoneNumber, queryDigits)) {
                    if (tryAdd(candidate)) return results
                }
            }
            for (candidate in contacts) {
                if (matchPhoneNumber(candidate.phoneNumber, queryDigits)) {
                    if (tryAdd(candidate)) return results
                }
            }
            return results.take(limit)
        }

        // 1. Exact match on contact displayName
        for (q in queryVariants) {
            for (candidate in contacts) {
                if (candidate.displayName.equals(q, ignoreCase = true)) {
                    if (tryAdd(candidate)) return results
                }
            }
        }
        if (results.size >= limit) return results.take(limit)

        // 2. Prefix match on contact displayName
        for (q in queryVariants) {
            val matches = contacts.filter { it.displayName.startsWith(q, ignoreCase = true) }
                .sortedBy { it.displayName.length }
            for (candidate in matches) {
                if (tryAdd(candidate)) return results
            }
        }
        if (results.size >= limit) return results.take(limit)

        // 3. Multi-word all-words prefix match
        for (q in queryVariants) {
            val variantWords = q.split("\\s+".toRegex()).filter { it.isNotEmpty() }
            if (variantWords.size > 1) {
                val matches = contacts.filter { candidate ->
                    val nameWords = candidate.displayName.split(" ", "-", "_", ".").filter { it.isNotEmpty() }
                    variantWords.all { qw ->
                        nameWords.any { nw -> nw.startsWith(qw, ignoreCase = true) }
                    }
                }.sortedBy { it.displayName.length }
                for (candidate in matches) {
                    if (tryAdd(candidate)) return results
                }
            }
        }
        if (results.size >= limit) return results.take(limit)

        // 4. Word-boundary prefix match
        for (q in queryVariants) {
            val matches = contacts.filter { candidate ->
                candidate.displayName.split(" ", "-", "_", ".").any { word ->
                    word.startsWith(q, ignoreCase = true)
                }
            }.sortedBy { it.displayName.length }
            for (candidate in matches) {
                if (tryAdd(candidate)) return results
            }
        }
        if (results.size >= limit) return results.take(limit)

        // 5. Contains match (restricted to variants of length >= 3)
        for (q in queryVariants) {
            if (q.length >= 3) {
                val matches = contacts.filter { it.displayName.contains(q, ignoreCase = true) }
                    .sortedBy { it.displayName.length }
                for (candidate in matches) {
                    if (tryAdd(candidate)) return results
                }
            }
        }

        return results.take(limit)
    }

    private fun matchPhoneNumber(candidatePhone: String?, queryDigits: String): Boolean {
        if (candidatePhone.isNullOrEmpty() || queryDigits.isEmpty()) return false
        val phoneDigits = candidatePhone.filter { it.isDigit() }
        if (phoneDigits.isEmpty()) return false

        if (queryDigits.length >= 2) {
            if (phoneDigits.contains(queryDigits)) return true
            if (queryDigits.startsWith("8")) {
                val alt = "7" + queryDigits.substring(1)
                if (phoneDigits.contains(alt)) return true
            } else if (queryDigits.startsWith("7")) {
                val alt = "8" + queryDigits.substring(1)
                if (phoneDigits.contains(alt)) return true
            }
        } else {
            if (phoneDigits.startsWith(queryDigits)) return true
        }
        return false
    }

    private fun matchPhoneNumberPrefix(candidatePhone: String?, queryDigits: String): Boolean {
        if (candidatePhone.isNullOrEmpty() || queryDigits.isEmpty()) return false
        val phoneDigits = candidatePhone.filter { it.isDigit() }
        if (phoneDigits.startsWith(queryDigits)) return true
        if (queryDigits.startsWith("8") && phoneDigits.startsWith("7" + queryDigits.substring(1))) return true
        if (queryDigits.startsWith("7") && phoneDigits.startsWith("8" + queryDigits.substring(1))) return true
        return false
    }

    fun createOpenContactIntent(candidate: ContactCandidate): Intent {
        val lookupUri = ContactsContract.Contacts.getLookupUri(candidate.contactId, candidate.lookupKey)
            ?: ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, candidate.contactId)
        return Intent(Intent.ACTION_VIEW, lookupUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun createDialContactIntent(candidate: ContactCandidate): Intent {
        val encodedPhone = Uri.encode(candidate.phoneNumber ?: "")
        return Intent(Intent.ACTION_DIAL, Uri.parse("tel:$encodedPhone")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
