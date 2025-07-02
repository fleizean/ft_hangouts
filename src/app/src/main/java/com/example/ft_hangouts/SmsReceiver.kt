package com.example.hangly

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
import java.util.concurrent.ConcurrentHashMap

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
        // Çoklu SMS mesajlarını geçici olarak saklayacak harita
        private val pendingMessages = ConcurrentHashMap<String, MutableList<SmsMessage>>()
        // Mesaj timeout süresi (5 dakika)
        private const val MESSAGE_TIMEOUT = 5 * 60 * 1000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "SMS received")

        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION == intent.action) {
            try {
                val smsMessages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

                if (smsMessages.isNotEmpty()) {
                    // Mesajları gönderen numaraya göre grupla
                    val messagesBySender = smsMessages.groupBy { it.originatingAddress }

                    for ((sender, messages) in messagesBySender) {
                        if (sender != null) {
                            processMessagesFromSender(context, sender, messages)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing SMS: ${e.message}", e)
            }
        }
    }

    private fun processMessagesFromSender(context: Context, sender: String, messages: List<SmsMessage>) {
        Log.d(TAG, "Processing ${messages.size} message(s) from: $sender")

        // Eğer tek mesaj ise direkt işle
        if (messages.size == 1 && !isMultipartMessage(messages[0])) {
            val messageBody = messages[0].messageBody
            Log.d(TAG, "Single message from $sender: $messageBody")
            saveSmsToDatabase(context, sender, messageBody)
            showSmsNotification(context, sender, messageBody)
            return
        }

        // Çoklu mesaj durumu - mesajları birleştir
        val combinedMessage = combineMultipartMessages(messages)
        Log.d(TAG, "Combined multipart message from $sender: $combinedMessage")

        saveSmsToDatabase(context, sender, combinedMessage)
        showSmsNotification(context, sender, combinedMessage)
    }

    private fun isMultipartMessage(smsMessage: SmsMessage): Boolean {
        return smsMessage.messageBody?.length ?: 0 > 160
    }

    private fun combineMultipartMessages(messages: List<SmsMessage>): String {
        val sortedMessages = messages.sortedBy { it.indexOnIcc }
        val combinedText = StringBuilder()

        for (message in sortedMessages) {
            val messageBody = message.messageBody
            if (!messageBody.isNullOrEmpty()) {
                combinedText.append(messageBody)
            }
        }

        return combinedText.toString()
    }

    private fun saveSmsToDatabase(context: Context, senderAddress: String?, messageBody: String) {
        if (senderAddress.isNullOrBlank() || messageBody.isBlank()) {
            Log.w(TAG, "Sender address or message body is null/empty, skipping")
            return
        }

        val dbHelper = DBHelper(context)
        val db = dbHelper.writableDatabase

        try {
            val senderType = SenderAddressHandler.getSenderType(senderAddress)
            Log.d(TAG, "Processing SMS from: $senderAddress, Type: $senderType, Length: ${messageBody.length}")

            val contactId = when (senderType) {
                SenderAddressHandler.SenderType.PHONE_NUMBER -> {
                    handlePhoneNumberSender(db, senderAddress, context)
                }
                SenderAddressHandler.SenderType.ALPHANUMERIC -> {
                    handleAlphanumericSender(db, senderAddress, context)
                }
                else -> {
                    handleAlphanumericSender(db, senderAddress, context)
                }
            }

            if (contactId != null) {
                // Mesajı kaydet
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val messageValues = ContentValues().apply {
                    put("contact_id", contactId)
                    put("message", messageBody)
                    put("sender", "other")
                    put("timestamp", timestamp)
                    put("read_status", 0)
                    put("message_length", messageBody.length)
                }

                val messageId = db.insert("messages", null, messageValues)
                if (messageId != -1L) {
                    Log.d(TAG, "SMS saved to database for contact ID: $contactId")
                } else {
                    Log.e(TAG, "Failed to save SMS to database")
                }
            } else {
                Log.e(TAG, "Failed to create or find contact for: $senderAddress")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving SMS: ${e.message}", e)
        } finally {
            db.close()
        }
    }

    private fun handlePhoneNumberSender(db: SQLiteDatabase, phoneNumber: String, context: Context): Long? {
        var contactId = PhoneNumberMatcher.findMatchingContactAdvanced(db, phoneNumber)

        if (contactId == null) {
            contactId = createNewPhoneContact(db, phoneNumber, context)
        }

        return contactId
    }

    private fun handleAlphanumericSender(db: SQLiteDatabase, address: String, context: Context): Long? {
        // Ortak SenderAddressHandler kullan
        val placeholderPhone = SenderAddressHandler.getPlaceholderPhone(address)
        val corporateName = SenderAddressHandler.createCorporateName(address)

        val cursor = db.rawQuery(
            "SELECT id FROM contacts WHERE phone = ? OR name = ?",
            arrayOf(placeholderPhone, corporateName)
        )

        var contactId: Long? = null
        if (cursor.moveToFirst()) {
            contactId = cursor.getLong(cursor.getColumnIndexOrThrow("id"))
            Log.d(TAG, "Found existing corporate contact: $contactId for $address -> $corporateName")
        }
        cursor.close()

        // Yoksa yeni kurumsal kişi oluştur
        if (contactId == null) {
            contactId = createNewCorporateContact(db, address, context)
        }

        return contactId
    }

    private fun createNewPhoneContact(db: SQLiteDatabase, phoneNumber: String, context: Context): Long? {
        val cleanNumber = PhoneNumberMatcher.normalizePhoneNumber(phoneNumber)
        val displayNumber = SenderAddressHandler.formatPhoneForDisplay(phoneNumber)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        val contactValues = ContentValues().apply {
            put("name", displayNumber)
            put("phone", phoneNumber)
            put("email", "")
            put("address", "")
            put("notes", context.getString(R.string.auto_created_from_sms, timestamp))
        }

        val newRowId = db.insert("contacts", null, contactValues)
        if (newRowId != -1L) {
            Log.d(TAG, "Created new phone contact with ID: $newRowId for number: $phoneNumber")
            return newRowId
        }
        return null
    }

    private fun createNewCorporateContact(db: SQLiteDatabase, address: String, context: Context): Long? {
        // Ortak SenderAddressHandler kullan
        val corporateName = SenderAddressHandler.createCorporateName(address)
        val placeholderPhone = SenderAddressHandler.getPlaceholderPhone(address)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        Log.d(TAG, "Creating new corporate contact: $corporateName for address: $address")

        val contactValues = ContentValues().apply {
            put("name", corporateName)
            put("phone", placeholderPhone)
            put("email", "")
            put("address", "")
            put("notes", context.getString(R.string.auto_created_from_sms, timestamp) + " (Kurumsal)")
        }

        val newRowId = db.insert("contacts", null, contactValues)
        if (newRowId != -1L) {
            Log.d(TAG, "Created new corporate contact with ID: $newRowId for address: $address")
            return newRowId
        } else {
            Log.e(TAG, "Failed to create corporate contact for address: $address")
            return null
        }
    }

    private fun showSmsNotification(context: Context, phoneNumber: String?, messageBody: String) {
        // Ortak SenderAddressHandler kullanarak kişi adını bul
        val senderName = if (!phoneNumber.isNullOrEmpty()) {
            // Önce veritabanından kişi adını bul
            val contactName = findContactName(context, phoneNumber)
            if (contactName != null) {
                contactName
            } else {
                // Kişi bulunamadıysa, ortak handler ile display name oluştur
                SenderAddressHandler.getDisplayNameForNotification(phoneNumber)
            }
        } else {
            "Bilinmeyen"
        }

        // NotificationHelper kullanarak bildirim göster
        val notificationText = if (messageBody.length > 100) {
            messageBody.take(100) + "..."
        } else {
            messageBody
        }

        Log.d(TAG, "Showing notification: Sender=$phoneNumber, Display=$senderName, Message preview=${notificationText.take(30)}...")
        NotificationHelper.showSmsNotification(context, senderName, notificationText)
    }

    private fun findContactName(context: Context, phoneNumber: String): String? {
        val dbHelper = DBHelper(context)
        val db = dbHelper.readableDatabase

        try {
            // Önce normal telefon numarasıyla ara
            var contactId = PhoneNumberMatcher.findMatchingContactAdvanced(db, phoneNumber)

            // Bulunamadıysa kurumsal gönderici olarak ara
            if (contactId == null) {
                val senderType = SenderAddressHandler.getSenderType(phoneNumber)
                if (senderType == SenderAddressHandler.SenderType.ALPHANUMERIC) {
                    val placeholderPhone = SenderAddressHandler.getPlaceholderPhone(phoneNumber)
                    val corporateName = SenderAddressHandler.createCorporateName(phoneNumber)

                    val cursor = db.rawQuery(
                        "SELECT name FROM contacts WHERE phone = ? OR name = ?",
                        arrayOf(placeholderPhone, corporateName)
                    )

                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                        cursor.close()
                        return name
                    }
                    cursor.close()
                }
                return null
            }

            // Normal kişi bulunduysa ismini getir
            val cursor = db.rawQuery("SELECT name FROM contacts WHERE id = ?", arrayOf(contactId.toString()))
            cursor.use { c ->
                if (c.moveToFirst()) {
                    return c.getString(c.getColumnIndexOrThrow("name"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding contact name: ${e.message}")
        } finally {
            db.close()
        }

        return null
    }
}