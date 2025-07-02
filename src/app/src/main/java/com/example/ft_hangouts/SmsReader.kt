package com.example.hangly

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

class SmsReader(private val context: Context) {

    companion object {
        private const val TAG = "SmsReader"
    }

    private val dbHelper = DBHelper(context)

    fun hasReadSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Kullanıcının tüm SMS'lerini çeker ve veritabanına aktarır
     */
    fun importAllSms(): ImportResult {
        if (!hasReadSmsPermission()) {
            return ImportResult(false, 0, context.getString(R.string.sms_read_permission_denied))
        }

        var importedCount = 0
        var skippedCount = 0

        try {
            // Gelen kutusu (Inbox)
            val inboxResult = importSmsFromUri(Telephony.Sms.Inbox.CONTENT_URI, "other")
            importedCount += inboxResult.imported
            skippedCount += inboxResult.skipped

            // Gönderilen kutusu (Sent)
            val sentResult = importSmsFromUri(Telephony.Sms.Sent.CONTENT_URI, "me")
            importedCount += sentResult.imported
            skippedCount += sentResult.skipped

            Log.d(TAG, "SMS import completed: $importedCount imported, $skippedCount skipped")

            return ImportResult(
                true,
                importedCount,
                context.getString(R.string.sms_import_success_result, importedCount, skippedCount)
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error importing SMS: ${e.message}")
            return ImportResult(false, 0, context.getString(R.string.sms_import_error_result, e.message))
        }
    }

    private fun importSmsFromUri(uri: Uri, senderType: String): ImportCounts {
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE
            ),
            null,
            null,
            "${Telephony.Sms.DATE} DESC LIMIT 3000"
        )

        var imported = 0
        var skipped = 0

        cursor?.use { c ->
            while (c.moveToNext()) {
                try {
                    val address = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS))
                    val body = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.BODY))
                    val date = c.getLong(c.getColumnIndexOrThrow(Telephony.Sms.DATE))

                    // Null/boş kontrolleri
                    if (body.isNullOrBlank()) {
                        Log.w(TAG, "Skipping SMS with empty body")
                        skipped++
                        continue
                    }

                    if (address.isNullOrBlank()) {
                        Log.w(TAG, "Skipping SMS with empty address")
                        skipped++
                        continue
                    }

                    Log.d(TAG, "Processing SMS from: '$address', Body length: ${body.length}")

                    // Kişi var mı kontrol et, yoksa oluştur
                    val contactId = getOrCreateContactFromAddress(address)

                    if (contactId > 0) {
                        // Bu mesaj zaten var mı kontrol et
                        if (!messageExists(contactId, body, date)) {
                            // Mesajı kaydet
                            val success = saveImportedMessage(contactId, body, senderType, date)
                            if (success) {
                                imported++
                                Log.d(TAG, "Imported SMS from: $address")
                            } else {
                                skipped++
                            }
                        } else {
                            skipped++
                            Log.d(TAG, "SMS already exists from: $address")
                        }
                    } else {
                        Log.w(TAG, "Failed to create contact for: $address")
                        skipped++
                    }

                } catch (e: Exception) {
                    Log.w(TAG, "Error processing SMS: ${e.message}")
                    skipped++
                }
            }
        }

        return ImportCounts(imported, skipped)
    }

    private fun getOrCreateContactFromAddress(address: String): Long {
        val db = dbHelper.writableDatabase

        return try {
            // Ortak SenderAddressHandler kullan
            val senderType = SenderAddressHandler.getSenderType(address)
            Log.d(TAG, "Address: '$address', Type: $senderType")

            when (senderType) {
                SenderAddressHandler.SenderType.PHONE_NUMBER -> {
                    // Normal telefon numarası
                    getOrCreatePhoneContact(address, db)
                }
                SenderAddressHandler.SenderType.ALPHANUMERIC -> {
                    // Kurumsal gönderici (SAMSUNG, TEB, vb.)
                    getOrCreateCorporateContact(address, db)
                }
                else -> {
                    // Diğer durumlar - alfanümerik olarak işle
                    getOrCreateCorporateContact(address, db)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getOrCreateContactFromAddress: ${e.message}")
            -1L
        }
    }

    private fun getOrCreateCorporateContact(address: String, db: SQLiteDatabase): Long {
        // Ortak SenderAddressHandler kullan
        val corporateName = SenderAddressHandler.createCorporateName(address)
        val placeholderPhone = SenderAddressHandler.getPlaceholderPhone(address)

        // Önce bu kurumsal gönderici zaten var mı kontrol et
        val cursor = db.rawQuery(
            "SELECT id FROM contacts WHERE phone = ? OR name = ?",
            arrayOf(placeholderPhone, corporateName)
        )

        var contactId: Long = -1
        if (cursor.moveToFirst()) {
            contactId = cursor.getLong(cursor.getColumnIndexOrThrow("id"))
            Log.d(TAG, "Found existing corporate contact: $contactId for $address -> $corporateName")
        }
        cursor.close()

        // Yoksa yeni kurumsal kişi oluştur
        if (contactId == -1L) {
            Log.d(TAG, "Creating new corporate contact: $corporateName for address: $address")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

            val values = ContentValues().apply {
                put("name", corporateName)
                put("phone", placeholderPhone) // CORP_BANKKART gibi
                put("email", "")
                put("address", "")
                put("notes", context.getString(R.string.auto_created_from_sms, timestamp) + " (Kurumsal)")
            }

            contactId = db.insert("contacts", null, values)
            if (contactId != -1L) {
                Log.d(TAG, "Created new corporate contact with ID: $contactId")
            }
        }

        return contactId
    }

    private fun getOrCreatePhoneContact(phoneNumber: String, db: SQLiteDatabase): Long {
        // Önce mevcut kişilerde bu numara var mı kontrol et
        var contactId = PhoneNumberMatcher.findMatchingContactAdvanced(db, phoneNumber)

        if (contactId != null) {
            Log.d(TAG, "Found existing phone contact with ID: $contactId for number: $phoneNumber")
            return contactId
        }

        // Kişi yoksa yeni oluştur
        Log.d(TAG, "Creating new phone contact for number: $phoneNumber")
        // Ortak SenderAddressHandler kullan
        val displayNumber = SenderAddressHandler.formatPhoneForDisplay(phoneNumber)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        val values = ContentValues().apply {
            put("name", displayNumber)
            put("phone", phoneNumber)
            put("email", "")
            put("address", "")
            put("notes", context.getString(R.string.auto_created_from_sms, timestamp))
        }

        return db.insert("contacts", null, values)
    }

    private fun messageExists(contactId: Long, messageBody: String, originalDate: Long): Boolean {
        val db = dbHelper.readableDatabase

        // Aynı kişi, aynı mesaj içeriği var mı kontrol et
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM messages WHERE contact_id = ? AND message = ?",
            arrayOf(contactId.toString(), messageBody)
        )

        var exists = false
        if (cursor.moveToFirst()) {
            exists = cursor.getInt(0) > 0
        }
        cursor.close()

        return exists
    }

    private fun saveImportedMessage(contactId: Long, messageBody: String, senderType: String, originalDate: Long): Boolean {
        val db = dbHelper.writableDatabase

        // Orijinal tarihi kullan
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(originalDate))

        val values = ContentValues().apply {
            put("contact_id", contactId)
            put("message", messageBody)
            put("sender", senderType)
            put("timestamp", timestamp)
            put("message_length", messageBody.length)
        }

        val result = db.insert("messages", null, values)
        return result != -1L
    }

    /**
     * Belirli bir tarih aralığındaki SMS'leri çeker
     */
    fun importSmsFromDateRange(startDate: Date, endDate: Date): ImportResult {
        if (!hasReadSmsPermission()) {
            return ImportResult(false, 0, context.getString(R.string.sms_read_permission_denied))
        }

        val startTime = startDate.time
        val endTime = endDate.time

        val selection = "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.DATE} <= ?"
        val selectionArgs = arrayOf(startTime.toString(), endTime.toString())

        // Implementation buraya eklenebilir...

        return ImportResult(true, 0, context.getString(R.string.date_range_not_implemented))
    }
}

data class ImportResult(
    val success: Boolean,
    val importedCount: Int,
    val message: String
)

data class ImportCounts(
    val imported: Int,
    val skipped: Int
)