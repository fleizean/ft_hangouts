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

    enum class SenderType {
        PHONE_NUMBER,    // Normal telefon numarası
        ALPHANUMERIC,    // Kurumsal/alfanümerik gönderici
        OTHER,           // Diğer formatlar
        UNKNOWN          // Bilinmeyen
    }


    object SenderAddressHandler {
    
    /**
     * Gönderici adresinin türünü belirler
     */
    fun getSenderType(address: String?): SenderType {
        if (address.isNullOrBlank()) return SenderType.UNKNOWN
        
        return when {
            // Sadece rakam ve +, - karakterleri içeriyorsa telefon numarası
            address.matches(Regex("^[+\\-0-9\\s()]+$")) -> SenderType.PHONE_NUMBER
            // Harf içeriyorsa alfanümerik (kurumsal)
            address.matches(Regex(".*[a-zA-Z].*")) -> SenderType.ALPHANUMERIC
            // Diğer durumlar
            else -> SenderType.OTHER
        }
    }
    
    /**
     * Kurumsal gönderici için kişi adı oluşturur
     */
    fun createCorporateName(address: String): String {
        return when (address.uppercase()) {
            "SAMSUNG" -> "Samsung Türkiye"
            "TEB" -> "TEB Bankası"
            "ZIRAAT" -> "Ziraat Bankası"
            "HALKBANK" -> "Halkbank"
            "ISBANK" -> "İş Bankası"
            "GARANTI" -> "Garanti BBVA"
            "AKBANK" -> "Akbank"
            "VAKIFBANK" -> "Vakıfbank"
            "YAPI" -> "Yapı Kredi"
            "FINANSBANK" -> "QNB Finansbank"
            "TURKCELL" -> "Turkcell"
            "VODAFONE" -> "Vodafone"
            "BIMCELL", "BİMCELL" -> "BiP"
            else -> address.uppercase() // Bilinmeyen kurumlar için büyük harfle
        }
    }
    
    /**
     * Alfanümerik gönderici için telefon numarası placeholder'ı
     */
    fun getPlaceholderPhone(address: String): String {
        // Alfanümerik gönderenler için özel format
        return "CORP_${address.uppercase()}"
    }
}


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
            SenderType.PHONE_NUMBER -> {
                // Normal telefon numarası - mevcut mantığı kullan
                handlePhoneNumberSender(db, senderAddress, context)
            }
            SenderType.ALPHANUMERIC -> {
                // Kurumsal gönderici - özel mantık
                handleAlphanumericSender(db, senderAddress, context)
            }
            else -> {
                // Diğer durumlar için de alfanümerik mantığını kullan
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
    // Mevcut telefon numarası mantığını kullan
    var contactId = PhoneNumberMatcher.findMatchingContactAdvanced(db, phoneNumber)
    
    if (contactId == null) {
        contactId = createNewPhoneContact(db, phoneNumber, context)
    }
    
    return contactId
}

private fun handleAlphanumericSender(db: SQLiteDatabase, address: String, context: Context): Long? {
    // Önce bu alfanümerik gönderici zaten var mı kontrol et
    val placeholderPhone = SenderAddressHandler.getPlaceholderPhone(address)
    
    val cursor = db.rawQuery(
        "SELECT id FROM contacts WHERE phone = ? OR name = ?", 
        arrayOf(placeholderPhone, SenderAddressHandler.createCorporateName(address))
    )
    
    var contactId: Long? = null
    if (cursor.moveToFirst()) {
        contactId = cursor.getLong(cursor.getColumnIndexOrThrow("id"))
        Log.d(TAG, "Found existing corporate contact: $contactId for $address")
    }
    cursor.close()
    
    // Yoksa yeni kurumsal kişi oluştur
    if (contactId == null) {
        contactId = createNewCorporateContact(db, address, context)
    }
    
    return contactId
}

private fun createNewPhoneContact(db: SQLiteDatabase, phoneNumber: String, context: Context): Long? {
    // Mevcut createNewContact metodunu kullan
    val cleanNumber = PhoneNumberMatcher.normalizePhoneNumber(phoneNumber)
    val displayNumber = formatPhoneForDisplay(phoneNumber)
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
    val corporateName = SenderAddressHandler.createCorporateName(address)
    val placeholderPhone = SenderAddressHandler.getPlaceholderPhone(address)
    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    Log.d(TAG, "Creating new corporate contact: $corporateName for address: $address")

    val contactValues = ContentValues().apply {
        put("name", corporateName)
        put("phone", placeholderPhone) // Özel format: CORP_SAMSUNG
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