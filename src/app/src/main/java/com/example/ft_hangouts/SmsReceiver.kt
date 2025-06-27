package com.example.ft_hangouts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.text.SimpleDateFormat
import java.util.*

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "SMS received")

        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION == intent.action) {
            val smsMessages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

            for (smsMessage in smsMessages) {
                val messageBody = smsMessage.messageBody
                val originatingAddress = smsMessage.originatingAddress

                Log.d(TAG, "Message from: $originatingAddress, Body: $messageBody")

                // SMS'i veritabanına kaydet
                saveSmsToDatabase(context, originatingAddress, messageBody)

                // Bildirim göster
                showSmsNotification(context, originatingAddress, messageBody)
            }
        }
    }

    private fun saveSmsToDatabase(context: Context, phoneNumber: String?, messageBody: String) {
        if (phoneNumber == null) return

        val dbHelper = DBHelper(context)
        val db = dbHelper.writableDatabase

        try {
            Log.d(TAG, "Processing SMS from: $phoneNumber")

            // Önce mevcut kişilerde bu numara var mı kontrol et
            var contactId = PhoneNumberMatcher.findMatchingContactAdvanced(db, phoneNumber)

            if (contactId == null) {
                Log.d(TAG, "No existing contact found, creating new contact")
                // Kişi yoksa yeni kişi oluştur
                contactId = createNewContact(
                    db, phoneNumber,
                    context = TODO()
                )
            } else {
                Log.d(TAG, "Found existing contact with ID: $contactId")
            }

            if (contactId != null) {
                // Mesajı kaydet
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val messageValues = ContentValues().apply {
                    put("contact_id", contactId)
                    put("message", messageBody)
                    put("sender", "other")
                    put("timestamp", timestamp)
                }

                db.insert("messages", null, messageValues)
                Log.d(TAG, "SMS saved to database for contact ID: $contactId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving SMS: ${e.message}")
        } finally {
            db.close()
        }
    }

    private fun createNewContact(db: SQLiteDatabase, phoneNumber: String,context: Context
    ): Long? {
        // Telefon numarasını temizle ve düzenle
        val cleanNumber = PhoneNumberMatcher.normalizePhoneNumber(phoneNumber)
        val displayNumber = formatPhoneForDisplay(phoneNumber)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        val contactValues = ContentValues().apply {
            put("name", displayNumber) // Güzel formatlanmış numarayı isim olarak kullan
            put("phone", phoneNumber) // Orijinal numarayı telefon olarak kaydet
            put("email", "")
            put("address", "")
            put("notes", context.getString(R.string.auto_created_from_sms, timestamp))
        }

        val newRowId = db.insert("contacts", null, contactValues)
        Log.d(TAG, "Created new contact with ID: $newRowId for number: $phoneNumber")
        return if (newRowId != -1L) newRowId else null
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

    private fun showSmsNotification(context: Context, phoneNumber: String?, messageBody: String) {
        // NotificationHelper kullanarak bildirim göster
        NotificationHelper.showSmsNotification(context, phoneNumber ?: "Bilinmeyen", messageBody)
    }
}