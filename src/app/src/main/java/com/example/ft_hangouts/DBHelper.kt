package com.example.ft_hangouts

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.ContentValues

class DBHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

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
        
        // Mesajlar tablosu oluştur
        db.execSQL("""
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                contact_id INTEGER,
                message TEXT,
                sender TEXT,
                timestamp TEXT,
                FOREIGN KEY (contact_id) REFERENCES contacts (id)
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS messages")
        db.execSQL("DROP TABLE IF EXISTS contacts")
        onCreate(db)
    }
    
    // Kişi silme metodu
    fun deleteContact(id: Int): Boolean {
        val db = this.writableDatabase
        // Önce ilgili kişinin mesajlarını siliyoruz (varsa)
        db.delete("messages", "contact_id = ?", arrayOf(id.toString()))
        // Sonra kişiyi siliyoruz
        val result = db.delete("contacts", "id = ?", arrayOf(id.toString()))
        db.close()
        return result > 0 // Silme başarılı olursa true döner
    }

    fun addTestMessage(contactId: Int, message: String, sender: String) {
            val db = this.writableDatabase
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            
            val values = ContentValues().apply {
                put("contact_id", contactId)
                put("message", message)
                put("sender", sender)
                put("timestamp", timestamp)
            }
            
            db.insert("messages", null, values)
        }
    
    fun addContact(name: String, phone: String, email: String, address: String, notes: String): Long {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put("name", name)
            put("phone", phone)
            put("email", email)
            put("address", address)
            put("notes", notes)
        }
        
        val id = db.insert("contacts", null, values)
        db.close()
        return id
    }

    // Kişi güncelleme metodu
    fun updateContact(id: Int, name: String, phone: String, email: String, address: String, notes: String): Boolean {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put("name", name)
            put("phone", phone)
            put("email", email)
            put("address", address)
            put("notes", notes)
        }
        
        val updated = db.update("contacts", values, "id = ?", arrayOf(id.toString()))
        db.close()
        return updated > 0
    }

    // Kişi bilgilerini alma metodu
    fun getContact(id: Int): ContentValues? {
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
            cursor.close()
            return contact
        }
        
        cursor.close()
        return null
    }

    // Mesaj gönderme metodu
    fun sendMessage(contactId: Int, messageText: String, sender: String): Long {
        val db = this.writableDatabase
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        
        val values = ContentValues().apply {
            put("contact_id", contactId)
            put("message", messageText)
            put("sender", sender)
            put("timestamp", timestamp)
        }
        
        val id = db.insert("messages", null, values)
        db.close()
        return id
    }

    // Mesajları alma metodu
    fun getMessages(contactId: Int): ArrayList<ContentValues> {
        val messages = ArrayList<ContentValues>()
        val db = this.readableDatabase
        
        val cursor = db.rawQuery(
            "SELECT * FROM messages WHERE contact_id = ? ORDER BY timestamp ASC",
            arrayOf(contactId.toString())
        )
        
        while (cursor.moveToNext()) {
            val message = ContentValues()
            message.put("id", cursor.getInt(cursor.getColumnIndexOrThrow("id")))
            message.put("contact_id", cursor.getInt(cursor.getColumnIndexOrThrow("contact_id")))
            message.put("message", cursor.getString(cursor.getColumnIndexOrThrow("message")))
            message.put("sender", cursor.getString(cursor.getColumnIndexOrThrow("sender")))
            message.put("timestamp", cursor.getString(cursor.getColumnIndexOrThrow("timestamp")))
            messages.add(message)
        }
        
        cursor.close()
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
            contacts.add(contact)
        }
        
        cursor.close()
        return contacts
    }

    companion object {
        const val DATABASE_NAME = "Hangouts.db"
        const val DATABASE_VERSION = 1
    }
}