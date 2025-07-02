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
import java.util.concurrent.ConcurrentHashMap  // Add this import

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

    /**
     * Bir mesajın çoklu parçalı olup olmadığını kontrol eder
     */
    private fun isMultipartMessage(smsMessage: SmsMessage): Boolean {
        // SMS uzunluğu 160 karakterden fazlaysa veya özel header varsa çoklu parçalı olabilir
        return smsMessage.messageBody?.length ?: 0 > 160
    }

    /**
     * Çoklu SMS mesajlarını birleştirir
     */
    private fun combineMultipartMessages(messages: List<SmsMessage>): String {
        val sortedMessages = messages.sortedBy { it.indexOnIcc } // SIM kart indeksine göre sırala
        val combinedText = StringBuilder()
        
        for (message in sortedMessages) {
            val messageBody = message.messageBody
            if (!messageBody.isNullOrEmpty()) {
                combinedText.append(messageBody)
            }
        }
        
        return combinedText.toString()
    }

    /**
     * Alternatif yöntem: Android'in kendi SMS işleme mekanizmasını kullan
     */
    private fun processWithAndroidSmsApi(context: Context, intent: Intent) {
        val bundle = intent.extras ?: return
        val pdus = bundle.get("pdus") as? Array<*> ?: return
        val format = bundle.getString("format")
        
        val messages = mutableListOf<SmsMessage>()
        
        for (pdu in pdus) {
            val bytes = pdu as ByteArray
            val smsMessage = if (format != null) {
                SmsMessage.createFromPdu(bytes, format)
            } else {
                @Suppress("DEPRECATION")
                SmsMessage.createFromPdu(bytes)
            }
            
            if (smsMessage != null) {
                messages.add(smsMessage)
            }
        }
        
        // Gönderen numaraya göre grupla
        val messagesByOrigin = messages.groupBy { it.originatingAddress }
        
        for ((origin, smsMessageList) in messagesByOrigin) {
            if (origin != null) {
                val fullMessage = buildCompleteMessage(smsMessageList)
                Log.d(TAG, "Complete message from $origin: $fullMessage")
                
                saveSmsToDatabase(context, origin, fullMessage)
                showSmsNotification(context, origin, fullMessage)
            }
        }
    }

    /**
     * SMS mesajlarından tam mesajı oluşturur
     */
    private fun buildCompleteMessage(messages: List<SmsMessage>): String {
        return when {
            messages.size == 1 -> {
                // Tek mesaj
                messages[0].messageBody ?: ""
            }
            messages.size > 1 -> {
                // Çoklu mesaj - sıralama yapıp birleştir
                messages.sortedBy { msg ->
                    // Timestamp'e göre sırala
                    msg.timestampMillis
                }.joinToString("") { msg ->
                    msg.messageBody ?: ""
                }
            }
            else -> ""
        }
    }

    private fun saveSmsToDatabase(context: Context, phoneNumber: String?, messageBody: String) {
        if (phoneNumber == null || phoneNumber.isBlank() || messageBody.isBlank()) {
            Log.w(TAG, "Phone number or message body is null/empty, skipping")
            return
        }

        val dbHelper = DBHelper(context)
        val db = dbHelper.writableDatabase

        try {
            Log.d(TAG, "Processing SMS from: $phoneNumber, Length: ${messageBody.length}")

            // Önce mevcut kişilerde bu numara var mı kontrol et
            var contactId = PhoneNumberMatcher.findMatchingContactAdvanced(db, phoneNumber)

            if (contactId == null) {
                Log.d(TAG, "No existing contact found, creating new contact")
                // Kişi yoksa yeni kişi oluştur
                contactId = createNewContact(db, phoneNumber, context)
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
                    put("read_status", 0) // Gelen mesaj okunmamış olarak işaretle
                }

                val messageId = db.insert("messages", null, messageValues)
                if (messageId != -1L) {
                    Log.d(TAG, "SMS saved to database for contact ID: $contactId, message ID: $messageId, Full message length: ${messageBody.length}")
                } else {
                    Log.e(TAG, "Failed to save SMS to database")
                }
            } else {
                Log.e(TAG, "Failed to create or find contact")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving SMS: ${e.message}", e)
        } finally {
            db.close()
        }
    }

    private fun createNewContact(db: SQLiteDatabase, phoneNumber: String, context: Context): Long? {
        // Telefon numarasını temizle ve düzenle
        val cleanNumber = PhoneNumberMatcher.normalizePhoneNumber(phoneNumber)
        val displayNumber = formatPhoneForDisplay(phoneNumber)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        Log.d(TAG, "Creating new contact: Original=$phoneNumber, Clean=$cleanNumber, Display=$displayNumber")

        val contactValues = ContentValues().apply {
            put("name", displayNumber) // Güzel formatlanmış numarayı isim olarak kullan
            put("phone", phoneNumber) // Orijinal numarayı telefon olarak kaydet
            put("email", "")
            put("address", "")
            put("notes", context.getString(R.string.auto_created_from_sms, timestamp))
        }

        val newRowId = db.insert("contacts", null, contactValues)
        if (newRowId != -1L) {
            Log.d(TAG, "Created new contact with ID: $newRowId for number: $phoneNumber")
            return newRowId
        } else {
            Log.e(TAG, "Failed to create new contact for number: $phoneNumber")
            return null
        }
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
        // Kişi adını bulmaya çalış
        val senderName = if (!phoneNumber.isNullOrEmpty()) {
            findContactName(context, phoneNumber) ?: formatPhoneForDisplay(phoneNumber)
        } else {
            "Bilinmeyen"
        }

        // NotificationHelper kullanarak bildirim göster
        // Uzun mesajlar için kısaltılmış versiyon göster
        val notificationText = if (messageBody.length > 100) {
            messageBody.take(100) + "..."
        } else {
            messageBody
        }
        
        NotificationHelper.showSmsNotification(context, senderName, notificationText)
    }

    private fun findContactName(context: Context, phoneNumber: String): String? {
        val dbHelper = DBHelper(context)
        val db = dbHelper.readableDatabase
        
        try {
            val contactId = PhoneNumberMatcher.findMatchingContactAdvanced(db, phoneNumber)
            if (contactId != null) {
                val cursor = db.rawQuery("SELECT name FROM contacts WHERE id = ?", arrayOf(contactId.toString()))
                cursor.use { c ->
                    if (c.moveToFirst()) {
                        return c.getString(c.getColumnIndexOrThrow("name"))
                    }
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