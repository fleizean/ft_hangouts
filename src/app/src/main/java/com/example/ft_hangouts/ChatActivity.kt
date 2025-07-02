package com.example.hangly

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MenuItem
import android.widget.Button
import android.util.Log
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.ContentValues

class ChatActivity : BaseActivity() {

    private lateinit var dbHelper: DBHelper
    private lateinit var messageListView: ListView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: Button
    private var contactId: Int = -1
    private var contactName: String = ""
    private var contactPhone: String = ""
    private lateinit var adapter: MessageAdapter
    private val messageList = mutableListOf<Message>()
    private lateinit var smsManagerHelper: SmsManagerHelper

    companion object {
        private const val SMS_PERMISSION_REQUEST = 101
        private const val TAG = "ChatActivity"
        private const val MAX_SMS_LENGTH = 160 // Standard SMS uzunluğu
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        // Toolbar ayarla
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        // Intent'ten verileri al
        contactId = intent.getIntExtra("contact_id", -1)
        contactName = intent.getStringExtra("contact_name") ?: getString(R.string.default_contact_name)

        supportActionBar?.title = contactName
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        dbHelper = DBHelper(this)
        smsManagerHelper = SmsManagerHelper(this)

        messageListView = findViewById(R.id.messageListView)
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)

        adapter = MessageAdapter()
        messageListView.adapter = adapter

        // Kişinin telefon numarasını al
        loadContactPhone()
        loadMessages()

        sendButton.setOnClickListener {
            sendRealSms()
        }
    }

    private fun loadContactPhone() {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT phone FROM contacts WHERE id = ?", arrayOf(contactId.toString()))

        if (cursor.moveToFirst()) {
            contactPhone = cursor.getString(cursor.getColumnIndexOrThrow("phone"))
        }
        cursor.close()
    }

    private fun sendRealSms() {
        val messageText = messageInput.text.toString().trim()

        if (messageText.isEmpty()) {
            Toast.makeText(this, getString(R.string.message_empty), Toast.LENGTH_SHORT).show()
            return
        }

        if (contactPhone.startsWith("CORP_")) {
            Toast.makeText(this, getString(R.string.cannot_sms_corporate), Toast.LENGTH_SHORT).show()
            return
        }

        if (contactPhone.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_valid_phone), Toast.LENGTH_SHORT).show()
            return
        }

        val messageLength = messageText.length
        Log.d(TAG, "Sending message of length: $messageLength")

        if (messageLength > MAX_SMS_LENGTH) {
            val partsCount = kotlin.math.ceil(messageLength.toDouble() / MAX_SMS_LENGTH).toInt()
            Toast.makeText(
                this, 
                getString(R.string.long_message_parts, messageLength, partsCount), 
                Toast.LENGTH_LONG
            ).show()
        }

        // SMS izinlerini kontrol et
        if (!smsManagerHelper.checkSmsPermissions()) {
            smsManagerHelper.requestSmsPermissions(this)
            return
        }

        // Gerçek SMS gönder
        val success = smsManagerHelper.sendSms(contactPhone, messageText)

        if (success) {
            // Mesajı veritabanına kaydet
            saveSentMessage(messageText)

            // Input'u temizle
            messageInput.text.clear()

            // Mesajları yeniden yükle
            loadMessages()
        } else {
            Toast.makeText(this, getString(R.string.message_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveSentMessage(messageText: String) {
        val currentTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("contact_id", contactId)
            put("message", messageText)
            put("sender", "me")
            put("timestamp", currentTime)
            put("message_length", messageText.length)
        }

        db.insert("messages", null, values)
    }

    private fun loadMessages() {
        messageList.clear()
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("""
            SELECT message, sender, timestamp, 
                   COALESCE(message_length, LENGTH(message)) as calculated_length
            FROM messages 
            WHERE contact_id = ? 
            ORDER BY id ASC
        """, arrayOf(contactId.toString()))


        while (cursor.moveToNext()) {
            val message = cursor.getString(cursor.getColumnIndexOrThrow("message"))
            val sender = cursor.getString(cursor.getColumnIndexOrThrow("sender"))
            val timestamp = cursor.getString(cursor.getColumnIndexOrThrow("timestamp"))
            val messageLength = cursor.getInt(cursor.getColumnIndexOrThrow("calculated_length"))

            messageList.add(Message(message, sender, timestamp, messageLength))
            
            Log.d(TAG, "Loaded message: sender=$sender, length=$messageLength, preview=${message.take(50)}...")
        }

        cursor.close()
        adapter.notifyDataSetChanged()

        // Son mesaja kaydır
        if (messageList.isNotEmpty()) {
            messageListView.setSelection(messageList.size - 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == SMS_PERMISSION_REQUEST) {
            var allPermissionsGranted = true
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false
                    break
                }
            }

            if (allPermissionsGranted) {
                Toast.makeText(this, getString(R.string.sms_permissions_granted), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.sms_permissions_denied), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        smsManagerHelper.unregisterReceivers()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // Message sınıfı ve MessageAdapter'ı buraya ekle (önceki kodla aynı)
    data class Message(val text: String, val sender: String, val timestamp: String, val length: Int = text.length)

    inner class MessageAdapter : android.widget.ArrayAdapter<Message>(
        this@ChatActivity,
        R.layout.item_message,
        messageList
    ) {
        override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_message, parent, false)

            val message = getItem(position)
            val messageText = view.findViewById<TextView>(R.id.messageText)
            val messageTime = view.findViewById<TextView>(R.id.messageTime)
            val messageLayout = view.findViewById<android.widget.LinearLayout>(R.id.messageLayout)

            message?.let { msg ->
                messageText.text = msg.text
                
                // Zaman formatına mesaj uzunluğunu da ekle (debug için)
                val timeDisplay = if (msg.length > MAX_SMS_LENGTH) {
                    "${msg.timestamp.substring(11, 16)} (${msg.length}ch)"
                } else {
                    msg.timestamp.substring(11, 16)
                }
                messageTime.text = timeDisplay

                val container = view.findViewById<android.widget.LinearLayout>(R.id.container)
                if (msg.sender == "me") {
                    messageLayout.setBackgroundResource(R.drawable.message_sent_background)
                    messageText.setTextColor(getColor(R.color.white))
                    container.gravity = android.view.Gravity.END
                } else {
                    messageLayout.setBackgroundResource(R.drawable.message_received_background)
                    messageText.setTextColor(getColor(R.color.black))
                    container.gravity = android.view.Gravity.START
                }

                // Uzun mesajlar için görsel ipucu
                if (msg.length > MAX_SMS_LENGTH) {
                    messageLayout.alpha = 0.9f // Hafif şeffaflık
                } else {
                    messageLayout.alpha = 1.0f
                }
            }

            return view
        }
    }
}