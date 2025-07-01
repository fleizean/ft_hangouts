package com.example.hangly

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MenuItem
import android.widget.Button
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
            Toast.makeText(this, "Mesaj boş olamaz", Toast.LENGTH_SHORT).show()
            return
        }

        if (contactPhone.isEmpty()) {
            Toast.makeText(this, "Geçerli telefon numarası bulunamadı", Toast.LENGTH_SHORT).show()
            return
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
            Toast.makeText(this, "SMS gönderilemedi", Toast.LENGTH_SHORT).show()
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
        }

        db.insert("messages", null, values)
    }

    private fun loadMessages() {
        messageList.clear()
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM messages WHERE contact_id = ? ORDER BY id ASC",
            arrayOf(contactId.toString())
        )

        while (cursor.moveToNext()) {
            val message = cursor.getString(cursor.getColumnIndexOrThrow("message"))
            val sender = cursor.getString(cursor.getColumnIndexOrThrow("sender"))
            val timestamp = cursor.getString(cursor.getColumnIndexOrThrow("timestamp"))

            messageList.add(Message(message, sender, timestamp))
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
                Toast.makeText(this, "SMS izinleri verildi", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "SMS izinleri reddedildi. Mesaj gönderilemez.", Toast.LENGTH_LONG).show()
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
    data class Message(val text: String, val sender: String, val timestamp: String)

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

            messageText.text = message?.text
            messageTime.text = message?.timestamp?.substring(11, 16)

            val container = view.findViewById<android.widget.LinearLayout>(R.id.container)
            if (message?.sender == "me") {
                messageLayout.setBackgroundResource(R.drawable.message_sent_background)
                messageText.setTextColor(getColor(R.color.white))
                container.gravity = android.view.Gravity.END
            } else {
                messageLayout.setBackgroundResource(R.drawable.message_received_background)
                messageText.setTextColor(getColor(R.color.black))
                container.gravity = android.view.Gravity.START
            }

            return view
        }
    }
}