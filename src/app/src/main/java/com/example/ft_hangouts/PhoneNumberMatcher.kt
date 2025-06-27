package com.example.ft_hangouts

import android.database.sqlite.SQLiteDatabase
import android.util.Log

object PhoneNumberMatcher {

    private const val TAG = "PhoneNumberMatcher"

    /**
     * Telefon numarasını normalize eder (sadece rakamlar kalır)
     */
    fun normalizePhoneNumber(phoneNumber: String): String {
        // Tüm karakterleri kaldır, sadece rakamları bırak
        val digitsOnly = phoneNumber.replace(Regex("[^0-9]"), "")

        // Türkiye için özel normalizasyon
        return when {
            // +90 ile başlıyorsa, +90'ı kaldır
            digitsOnly.startsWith("90") && digitsOnly.length == 12 -> {
                digitsOnly.substring(2) // "905551234567" -> "5551234567"
            }
            // 0 ile başlıyorsa, 0'ı kaldır
            digitsOnly.startsWith("0") && digitsOnly.length == 11 -> {
                digitsOnly.substring(1) // "05551234567" -> "5551234567"
            }
            // 10 haneli ise (0532...) olduğu varsayılır
            digitsOnly.length == 10 -> {
                digitsOnly // "5551234567" -> "5551234567"
            }
            else -> {
                digitsOnly // Ham hali döndür
            }
        }
    }

    /**
     * SMS'den gelen numarayı mevcut kişilerle eşleştirir
     */
    fun findMatchingContact(db: SQLiteDatabase, incomingNumber: String): Long? {
        val normalizedIncoming = normalizePhoneNumber(incomingNumber)

        Log.d(TAG, "Looking for contact with number: $incomingNumber -> normalized: $normalizedIncoming")

        // Tüm kişileri kontrol et
        val cursor = db.rawQuery("SELECT id, name, phone FROM contacts", null)

        cursor.use { c ->
            while (c.moveToNext()) {
                val contactId = c.getLong(c.getColumnIndexOrThrow("id"))
                val contactName = c.getString(c.getColumnIndexOrThrow("name"))
                val contactPhone = c.getString(c.getColumnIndexOrThrow("phone"))

                val normalizedContact = normalizePhoneNumber(contactPhone)

                Log.d(TAG, "Checking contact: $contactName ($contactPhone -> $normalizedContact)")

                if (normalizedIncoming == normalizedContact) {
                    Log.d(TAG, "MATCH FOUND! Contact: $contactName")
                    return contactId
                }
            }
        }

        Log.d(TAG, "No matching contact found for: $incomingNumber")
        return null
    }

    /**
     * Çoklu eşleştirme seçenekleri dener (daha kapsamlı)
     */
    fun findMatchingContactAdvanced(db: SQLiteDatabase, incomingNumber: String): Long? {
        val normalizedIncoming = normalizePhoneNumber(incomingNumber)

        // 1. Tam eşleştirme dene
        var contactId = findExactMatch(db, normalizedIncoming)
        if (contactId != null) return contactId

        // 2. Son 7 haneli eşleştirme dene (şehir kodu olmadan)
        if (normalizedIncoming.length >= 7) {
            val last7Digits = normalizedIncoming.takeLast(7)
            contactId = findPartialMatch(db, last7Digits)
            if (contactId != null) return contactId
        }

        // 3. Son 10 haneli eşleştirme dene
        if (normalizedIncoming.length >= 10) {
            val last10Digits = normalizedIncoming.takeLast(10)
            contactId = findPartialMatch(db, last10Digits)
            if (contactId != null) return contactId
        }

        return null
    }

    private fun findExactMatch(db: SQLiteDatabase, normalizedNumber: String): Long? {
        val cursor = db.rawQuery("SELECT id, phone FROM contacts", null)

        cursor.use { c ->
            while (c.moveToNext()) {
                val contactId = c.getLong(c.getColumnIndexOrThrow("id"))
                val contactPhone = c.getString(c.getColumnIndexOrThrow("phone"))
                val normalizedContact = normalizePhoneNumber(contactPhone)

                if (normalizedNumber == normalizedContact) {
                    return contactId
                }
            }
        }
        return null
    }

    private fun findPartialMatch(db: SQLiteDatabase, partialNumber: String): Long? {
        val cursor = db.rawQuery("SELECT id, phone FROM contacts", null)

        cursor.use { c ->
            while (c.moveToNext()) {
                val contactId = c.getLong(c.getColumnIndexOrThrow("id"))
                val contactPhone = c.getString(c.getColumnIndexOrThrow("phone"))
                val normalizedContact = normalizePhoneNumber(contactPhone)

                // Son X haneli eşleştirme
                if (normalizedContact.endsWith(partialNumber)) {
                    Log.d(TAG, "Partial match found: $contactPhone matches with $partialNumber")
                    return contactId
                }
            }
        }
        return null
    }

    /**
     * Test için - numara normalizasyonunu kontrol et
     */
    fun testNormalization() {
        val testNumbers = listOf(
            "+90 555 123 45 67",
            "0555 123 45 67",
            "555-123-4567",
            "5551234567",
            "+905551234567",
            "05551234567"
        )

        testNumbers.forEach { number ->
            val normalized = normalizePhoneNumber(number)
            Log.d(TAG, "Test: '$number' -> '$normalized'")
        }
    }
}