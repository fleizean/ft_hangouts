package com.example.ft_hangouts

import android.os.Bundle
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import android.widget.ArrayAdapter
import android.content.ContentValues
import android.content.Intent
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatActivity : BaseActivity() {

    private lateinit var dbHelper: DBHelper
    private lateinit var messageListView: ListView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: Button
    private var contactId: Int = -1
    private var contactName: String = ""
    private lateinit var adapter: MessageAdapter
    private val messageList = mutableListOf<Message>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        // Toolbar'ı ayarla
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        
        // Intent'ten verileri al
        contactId = intent.getIntExtra("contact_id", -1)
        contactName = intent.getStringExtra("contact_name") ?: "Kişi"
        
        supportActionBar?.title = contactName
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        dbHelper = DBHelper(this)
        messageListView = findViewById(R.id.messageListView)
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)

        adapter = MessageAdapter()
        messageListView.adapter = adapter

        loadMessages()

        sendButton.setOnClickListener {
            sendMessage()
        }
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

    private fun sendMessage() {
        val messageText = messageInput.text.toString().trim()
        
        if (messageText.isEmpty()) return
        
        val currentTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("contact_id", contactId)
            put("message", messageText)
            put("sender", "me")  // "me" kullanıcının gönderdiği mesajları belirtir
            put("timestamp", currentTime)
        }
        
        db.insert("messages", null, values)
        
        // Mesaj input alanını temizle
        messageInput.text.clear()
        
        // Mesajları yeniden yükle
        loadMessages()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.menu_chat, menu)
    return true
}

override fun onOptionsItemSelected(item: MenuItem): Boolean {
    return when (item.itemId) {
        R.id.action_simulate_message -> {
            simulateIncomingMessage()
            true
        }
        android.R.id.home -> {
            finish()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}

private fun simulateIncomingMessage() {
    val unknownNumber = "+90" + (1000000000..9999999999).random()
    
    // Numara kişilerde var mı kontrol et
    val db = dbHelper.readableDatabase
    val cursor = db.rawQuery("SELECT * FROM contacts WHERE phone = ?", arrayOf(unknownNumber))
    
    if (cursor.count == 0) {
        // Yeni kişi oluştur ve direkt olarak rehbere ekle
        val contactValues = ContentValues().apply {
            put("name", "Bilinmeyen Numara")  // Default isim
            put("phone", unknownNumber)
            // Diğer varsayılan alanlar eklenebilir
            put("email", "")
            put("address", "")
        }
        
        val contactId = db.insert("contacts", null, contactValues)
        
        // Yeni mesaj ekle
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val messageValues = ContentValues().apply {
            put("contact_id", contactId)
            put("message", "Merhaba, ben bilinmeyen numara!")
            put("sender", "other")
            put("timestamp", timestamp)
        }
        
        db.insert("messages", null, messageValues)
        
        // Kullanıcıyı bilgilendir
        Toast.makeText(
            this, 
            "Bilinmeyen bir numaradan ($unknownNumber) mesaj aldınız. Kişi rehbere eklendi.", 
            Toast.LENGTH_LONG
        ).show()
    } else {
        Toast.makeText(this, "Bu numara zaten kişilerinizde mevcut", Toast.LENGTH_SHORT).show()
    }
    
    cursor.close()
}


    
    // Dahili Message veri sınıfı
    data class Message(val text: String, val sender: String, val timestamp: String)
    
    // Özel Adapter
    inner class MessageAdapter : ArrayAdapter<Message>(
        this@ChatActivity, 
        R.layout.item_message, 
        messageList
    ) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_message, parent, false)
        
            val message = getItem(position)
            val messageText = view.findViewById<TextView>(R.id.messageText)
            val messageTime = view.findViewById<TextView>(R.id.messageTime)
            val messageLayout = view.findViewById<LinearLayout>(R.id.messageLayout)
            
            messageText.text = message?.text
            messageTime.text = message?.timestamp?.substring(11, 16)  // Sadece saat:dakika
            
            val container = view.findViewById<LinearLayout>(R.id.container)
            if (message?.sender == "me") {
                messageLayout.setBackgroundResource(R.drawable.message_sent_background)
                messageText.setTextColor(getColor(R.color.white))
                container.gravity = Gravity.END  // Sağa hizala
            } else {
                messageLayout.setBackgroundResource(R.drawable.message_received_background)
                messageText.setTextColor(getColor(R.color.black))
                container.gravity = Gravity.START  // Sola hizala
            }
            
            return view

        }
    }
}