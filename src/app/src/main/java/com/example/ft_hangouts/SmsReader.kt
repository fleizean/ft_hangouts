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
            "${Telephony.Sms.DATE} DESC LIMIT 3000" // Son 1000 SMS
        )

        var imported = 0
        var skipped = 0

        cursor?.use { c ->
            while (c.moveToNext()) {
                try {
                    val address = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: continue
                    val body = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: continue
                    val date = c.getLong(c.getColumnIndexOrThrow(Telephony.Sms.DATE))

                    // Telefon numarasını temizle
                    val cleanAddress = cleanPhoneNumber(address)

                    // Kişi var mı kontrol et, yoksa oluştur
                    val contactId = getOrCreateContact(cleanAddress)

                    // Bu mesaj zaten var mı kontrol et
                    if (!messageExists(contactId, body, date)) {
                        // Mesajı kaydet
                        val success = saveImportedMessage(contactId, body, senderType, date)
                        if (success) {
                            imported++
                        } else {
                            skipped++
                        }
                    } else {
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

    private fun cleanPhoneNumber(phone: String): String {
        // Telefon numarasını temizle (+90, boşluk, tire vb. kaldır)
        return phone.replace(Regex("[^0-9+]"), "")
    }

    private fun getOrCreateContact(phoneNumber: String): Long {
        val db = dbHelper.writableDatabase

        // Önce mevcut kişilerde bu numara var mı kontrol et
        var contactId = PhoneNumberMatcher.findMatchingContactAdvanced(db, phoneNumber)

        if (contactId != null) {
            Log.d(TAG, "Found existing contact with ID: $contactId for number: $phoneNumber")
            return contactId
        }

        // Kişi yoksa yeni oluştur
        Log.d(TAG, "Creating new contact for number: $phoneNumber")
        val displayNumber = formatPhoneForDisplay(phoneNumber)

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        val values = ContentValues().apply {
            put("name", displayNumber) // Güzel formatlanmış numarayı isim olarak kullan
            put("phone", phoneNumber) // Orijinal numarayı telefon olarak kaydet
            put("email", "")
            put("address", "")
            put("notes", context.getString(R.string.auto_created_from_sms, timestamp))
        }

        return db.insert("contacts", null, values)
    }

    private fun formatPhoneForDisplay(phoneNumber: String): String {
        val normalized = PhoneNumberMatcher.normalizePhoneNumber(phoneNumber)

        // Türkiye formatında güzel gösterim
        return when (normalized.length) {
            10 -> {
                // 5551234567 -> 0555 123 45 67
                "0${normalized.substring(0,3)} ${normalized.substring(3,6)} ${normalized.substring(6,8)} ${normalized.substring(8)}"
            }
            else -> phoneNumber // Ham halini döndür
        }
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