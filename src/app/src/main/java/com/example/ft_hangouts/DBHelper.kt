package com.example.hangly

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import android.util.Log
import java.util.Locale
import android.content.ContentValues

class DBHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "Hangouts.db"
        const val DATABASE_VERSION = 3  // Mesaj alanı için version artırıldı
        private const val TAG = "DBHelper"
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Kişiler tablosu oluştur
        db.execSQL("""
            CREATE TABLE contacts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                phone TEXT,
                email TEXT,
                address TEXT,
                notes TEXT,
                image TEXT
            )
        """)

        // Mesajlar tablosu oluştur - TEXT alanı unlimited karakter destekler
        db.execSQL("""
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                contact_id INTEGER,
                message TEXT,
                sender TEXT,
                timestamp TEXT,
                read_status INTEGER DEFAULT 0,
                message_length INTEGER DEFAULT 0,
                FOREIGN KEY (contact_id) REFERENCES contacts (id)
            )
        """)
        
        Log.d(TAG, "Database tables created successfully")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.d(TAG, "Upgrading database from version $oldVersion to $newVersion")
        
        when (oldVersion) {
            1 -> {
                // Version 1'den 2'ye: read_status sütunu eklendi
                try {
                    db.execSQL("ALTER TABLE messages ADD COLUMN read_status INTEGER DEFAULT 0")
                    Log.d(TAG, "Added read_status column")
                } catch (e: Exception) {
                    Log.w(TAG, "read_status column might already exist: ${e.message}")
                }
                
                if (newVersion > 2) {
                    // Version 3'e devam et
                    upgradeToVersion3(db)
                }
            }
            2 -> {
                // Version 2'den 3'e: message_length sütunu eklendi
                upgradeToVersion3(db)
            }
        }
    }

    private fun upgradeToVersion3(db: SQLiteDatabase) {
        try {
            // Mesaj uzunluk sütunu ekle
            db.execSQL("ALTER TABLE messages ADD COLUMN message_length INTEGER DEFAULT 0")
            
            // Mevcut mesajların uzunluklarını hesapla ve güncelle
            updateExistingMessageLengths(db)
            
            Log.d(TAG, "Added message_length column and updated existing messages")
        } catch (e: Exception) {
            Log.w(TAG, "message_length column might already exist: ${e.message}")
        }
    }

    private fun updateExistingMessageLengths(db: SQLiteDatabase) {
        val cursor = db.rawQuery("SELECT id, message FROM messages WHERE message_length = ?", arrayOf("0"))
        
        while (cursor.moveToNext()) {
            val messageId = cursor.getLong(cursor.getColumnIndexOrThrow("id"))
            val messageText = cursor.getString(cursor.getColumnIndexOrThrow("message")) ?: ""
            val messageLength = messageText.length
            
            val updateValues = ContentValues().apply {
                put("message_length", messageLength)
            }
            
            db.update("messages", updateValues, "id = ?", arrayOf(messageId.toString()))
        }
        
        cursor.close()
        Log.d(TAG, "Updated message lengths for existing messages")
    }

    // Kişi silme metodu
    fun deleteContact(id: Int): Boolean {
        val db = this.writableDatabase
        
        // Input validation
        if (id <= 0) {
            Log.w(TAG, "Invalid contact ID: $id")
            return false
        }
        
        try {
            db.beginTransaction()
            
            // Önce ilgili kişinin mesajlarını sil
            val deletedMessages = db.delete("messages", "contact_id = ?", arrayOf(id.toString()))
            
            // Sonra kişiyi sil
            val deletedContact = db.delete("contacts", "id = ?", arrayOf(id.toString()))
            
            if (deletedContact > 0) {
                db.setTransactionSuccessful()
                Log.d(TAG, "Deleted contact $id and $deletedMessages messages")
                return true
            } else {
                Log.w(TAG, "Contact $id not found")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting contact $id: ${e.message}")
            return false
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    fun addContact(name: String, phone: String, email: String, address: String, notes: String): Long {
        // Input validation
        if (name.isBlank()) {
            Log.w(TAG, "Contact name cannot be blank")
            return -1
        }
        
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put("name", name.trim())
            put("phone", phone.trim())
            put("email", email.trim())
            put("address", address.trim())
            put("notes", notes.trim())
        }

        val id = db.insert("contacts", null, values)
        db.close()
        return id
    }

    // Kişi güncelleme metodu
    fun updateContact(id: Int, name: String, phone: String, email: String, address: String, notes: String): Boolean {
        // Input validation
        if (id <= 0) {
            Log.w(TAG, "Invalid contact ID: $id")
            return false
        }
        if (name.isBlank()) {
            Log.w(TAG, "Contact name cannot be blank")
            return false
        }
        
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put("name", name.trim())
            put("phone", phone.trim())
            put("email", email.trim())
            put("address", address.trim())
            put("notes", notes.trim())
        }

        val updated = db.update("contacts", values, "id = ?", arrayOf(id.toString()))
        db.close()
        return updated > 0
    }

    // Kişi bilgilerini alma metodu
    fun getContact(id: Int): ContentValues? {
        // Input validation
        if (id <= 0) {
            Log.w(TAG, "Invalid contact ID: $id")
            return null
        }
        
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM contacts WHERE id = ?", arrayOf(id.toString()))
        val contact = ContentValues()

        if (cursor.moveToFirst()) {
            contact.put("id", cursor.getInt(cursor.getColumnIndexOrThrow("id")))
            contact.put("name", cursor.getString(cursor.getColumnIndexOrThrow("name")))
            contact.put("phone", cursor.getString(cursor.getColumnIndexOrThrow("phone")))
            contact.put("email", cursor.getString(cursor.getColumnIndexOrThrow("email")))
            contact.put("address", cursor.getString(cursor.getColumnIndexOrThrow("address")))
            contact.put("notes", cursor.getString(cursor.getColumnIndexOrThrow("notes")))
            
            // image sütunu varsa al
            try {
                contact.put("image", cursor.getString(cursor.getColumnIndexOrThrow("image")))
            } catch (e: Exception) {
                contact.put("image", "")
            }
            
            cursor.close()
            return contact
        }

        cursor.close()
        return null
    }

    // Mesaj gönderme metodu
    fun sendMessage(contactId: Int, messageText: String, sender: String): Long {
        // Input validation
        if (contactId <= 0) {
            Log.w(TAG, "Invalid contact ID: $contactId")
            return -1
        }
        if (messageText.isBlank()) {
            Log.w(TAG, "Message text cannot be blank")
            return -1
        }
        if (sender != "me" && sender != "other") {
            Log.w(TAG, "Invalid sender: $sender")
            return -1
        }
        
        val db = this.writableDatabase
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        val values = ContentValues().apply {
            put("contact_id", contactId)
            put("message", messageText)
            put("sender", sender)
            put("timestamp", timestamp)
            put("read_status", if (sender == "me") 1 else 0)
            put("message_length", messageText.length)
        }

        val id = db.insert("messages", null, values)
        Log.d(TAG, "Message sent with ID: $id, Length: ${messageText.length}, Contact: $contactId")
        db.close()
        return id
    }

    // Mesajları alma metodu
    fun getMessages(contactId: Int): ArrayList<ContentValues> {
        val messages = ArrayList<ContentValues>()
        
        // Input validation
        if (contactId <= 0) {
            Log.w(TAG, "Invalid contact ID: $contactId")
            return messages
        }
        
        val db = this.readableDatabase

        val cursor = db.rawQuery(
            """
            SELECT id, contact_id, message, sender, timestamp, read_status, 
                   COALESCE(message_length, LENGTH(message)) as calculated_length
            FROM messages 
            WHERE contact_id = ? 
            ORDER BY timestamp ASC
            """,
            arrayOf(contactId.toString())
        )

        while (cursor.moveToNext()) {
            val message = ContentValues()
            message.put("id", cursor.getInt(cursor.getColumnIndexOrThrow("id")))
            message.put("contact_id", cursor.getInt(cursor.getColumnIndexOrThrow("contact_id")))
            
            val messageText = cursor.getString(cursor.getColumnIndexOrThrow("message"))
            message.put("message", messageText)
            message.put("sender", cursor.getString(cursor.getColumnIndexOrThrow("sender")))
            message.put("timestamp", cursor.getString(cursor.getColumnIndexOrThrow("timestamp")))

            // read_status sütunu varsa al
            try {
                message.put("read_status", cursor.getInt(cursor.getColumnIndexOrThrow("read_status")))
            } catch (e: Exception) {
                message.put("read_status", 1)
            }
            
            // Mesaj uzunluğunu al
            try {
                val calculatedLength = cursor.getInt(cursor.getColumnIndexOrThrow("calculated_length"))
                message.put("message_length", calculatedLength)
                
                // Uzunluk kontrolü - güvenlik için
                val actualLength = messageText?.length ?: 0
                if (calculatedLength != actualLength) {
                    Log.w(TAG, "Message length mismatch: stored=$calculatedLength, actual=$actualLength")
                }
            } catch (e: Exception) {
                val actualLength = messageText?.length ?: 0
                message.put("message_length", actualLength)
            }

            messages.add(message)
        }

        cursor.close()
        Log.d(TAG, "Retrieved ${messages.size} messages for contact $contactId")
        return messages
    }


    // Tüm kişileri alma metodu
    fun getAllContacts(): ArrayList<ContentValues> {
        val contacts = ArrayList<ContentValues>()
        val db = this.readableDatabase

        val cursor = db.rawQuery("SELECT * FROM contacts ORDER BY name ASC", null)

        while (cursor.moveToNext()) {
            val contact = ContentValues()
            contact.put("id", cursor.getInt(cursor.getColumnIndexOrThrow("id")))
            contact.put("name", cursor.getString(cursor.getColumnIndexOrThrow("name")))
            contact.put("phone", cursor.getString(cursor.getColumnIndexOrThrow("phone")))
            contact.put("email", cursor.getString(cursor.getColumnIndexOrThrow("email")))
            contact.put("address", cursor.getString(cursor.getColumnIndexOrThrow("address")))
            contact.put("notes", cursor.getString(cursor.getColumnIndexOrThrow("notes")))

            // image sütunu varsa al
            try {
                contact.put("image", cursor.getString(cursor.getColumnIndexOrThrow("image")))
            } catch (e: Exception) {
                contact.put("image", "")
            }

            contacts.add(contact)
        }

        cursor.close()
        return contacts
    }

    // YENİ: Tüm kişileri mesaj bilgileriyle birlikte alma metodu
    fun getAllContactsWithMessages(): ArrayList<ContactWithMessages> {
        val contacts = ArrayList<ContactWithMessages>()
        val db = this.readableDatabase

        val query = """
            SELECT 
                c.id,
                c.name,
                c.phone,
                COALESCE(c.email, '') as email,
                COALESCE(c.address, '') as address,
                COALESCE(c.notes, '') as notes,
                COALESCE(c.image, '') as image,
                COALESCE((SELECT m.message FROM messages m WHERE m.contact_id = c.id ORDER BY m.timestamp DESC LIMIT 1), '') as last_message,
                COALESCE((SELECT m.timestamp FROM messages m WHERE m.contact_id = c.id ORDER BY m.timestamp DESC LIMIT 1), '') as last_message_time,
                COALESCE((SELECT COUNT(*) FROM messages m WHERE m.contact_id = c.id AND m.sender = 'other' AND COALESCE(m.read_status, 0) = 0), 0) as unread_count,
                COALESCE((SELECT COUNT(*) FROM messages m WHERE m.contact_id = c.id), 0) as total_count,
                COALESCE((SELECT MAX(COALESCE(m.message_length, LENGTH(m.message))) FROM messages m WHERE m.contact_id = c.id), 0) as max_message_length,
                COALESCE((SELECT AVG(COALESCE(m.message_length, LENGTH(m.message))) FROM messages m WHERE m.contact_id = c.id), 0) as avg_message_length
            FROM contacts c
            ORDER BY 
                CASE WHEN last_message_time = '' THEN 1 ELSE 0 END,
                last_message_time DESC,
                c.name ASC
        """

        val cursor = db.rawQuery(query, null)

        while (cursor.moveToNext()) {
            val contact = ContactWithMessages(
                id = cursor.getInt(cursor.getColumnIndexOrThrow("id")),
                name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                phone = cursor.getString(cursor.getColumnIndexOrThrow("phone")),
                email = cursor.getString(cursor.getColumnIndexOrThrow("email")),
                address = cursor.getString(cursor.getColumnIndexOrThrow("address")),
                notes = cursor.getString(cursor.getColumnIndexOrThrow("notes")),
                image = cursor.getString(cursor.getColumnIndexOrThrow("image")),
                lastMessage = cursor.getString(cursor.getColumnIndexOrThrow("last_message")),
                lastMessageTime = cursor.getString(cursor.getColumnIndexOrThrow("last_message_time")),
                unreadCount = cursor.getInt(cursor.getColumnIndexOrThrow("unread_count")),
                totalMessageCount = cursor.getInt(cursor.getColumnIndexOrThrow("total_count")),
            )
            contacts.add(contact)
        }

        cursor.close()
        Log.d(TAG, "Retrieved ${contacts.size} contacts with message info")
        return contacts
    }

    /**
     * Mesaj uzunluk istatistiklerini al
     */
    fun getMessageLengthStats(): MessageStats {
        val db = this.readableDatabase
        
        val cursor = db.rawQuery("""
            SELECT 
                COUNT(*) as total_messages,
                AVG(COALESCE(message_length, LENGTH(message))) as avg_length,
                MAX(COALESCE(message_length, LENGTH(message))) as max_length,
                MIN(COALESCE(message_length, LENGTH(message))) as min_length,
                COUNT(CASE WHEN COALESCE(message_length, LENGTH(message)) > 160 THEN 1 END) as long_messages
            FROM messages
        """, null)
        
        var stats = MessageStats()
        
        if (cursor.moveToFirst()) {
            stats = MessageStats(
                totalMessages = cursor.getInt(cursor.getColumnIndexOrThrow("total_messages")),
                averageLength = cursor.getDouble(cursor.getColumnIndexOrThrow("avg_length")),
                maxLength = cursor.getInt(cursor.getColumnIndexOrThrow("max_length")),
                minLength = cursor.getInt(cursor.getColumnIndexOrThrow("min_length")),
                longMessages = cursor.getInt(cursor.getColumnIndexOrThrow("long_messages"))
            )
        }
        
        cursor.close()
        Log.d(TAG, "Message stats: $stats")
        return stats
    }

    /**
     * Belirli uzunluktan uzun mesajları getir
     */
    fun getLongMessages(minLength: Int = 160): ArrayList<ContentValues> {
        val messages = ArrayList<ContentValues>()
        
        // Input validation
        if (minLength < 0) {
            Log.w(TAG, "Invalid minLength: $minLength")
            return messages
        }
        
        val db = this.readableDatabase

        val cursor = db.rawQuery("""
            SELECT m.*, c.name as contact_name, 
                   COALESCE(m.message_length, LENGTH(m.message)) as calculated_length
            FROM messages m
            LEFT JOIN contacts c ON m.contact_id = c.id
            WHERE COALESCE(m.message_length, LENGTH(m.message)) > ?
            ORDER BY calculated_length DESC
        """, arrayOf(minLength.toString()))

        while (cursor.moveToNext()) {
            val message = ContentValues()
            message.put("id", cursor.getInt(cursor.getColumnIndexOrThrow("id")))
            message.put("contact_id", cursor.getInt(cursor.getColumnIndexOrThrow("contact_id")))
            message.put("message", cursor.getString(cursor.getColumnIndexOrThrow("message")))
            message.put("sender", cursor.getString(cursor.getColumnIndexOrThrow("sender")))
            message.put("timestamp", cursor.getString(cursor.getColumnIndexOrThrow("timestamp")))
            message.put("contact_name", cursor.getString(cursor.getColumnIndexOrThrow("contact_name")))
            message.put("calculated_length", cursor.getInt(cursor.getColumnIndexOrThrow("calculated_length")))

            messages.add(message)
        }

        cursor.close()
        Log.d(TAG, "Found ${messages.size} messages longer than $minLength characters")
        return messages
    }
}

/**
 * Mesaj istatistikleri için data class
 */
data class MessageStats(
    val totalMessages: Int = 0,
    val averageLength: Double = 0.0,
    val maxLength: Int = 0,
    val minLength: Int = 0,
    val longMessages: Int = 0
)