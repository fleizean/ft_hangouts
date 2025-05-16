package com.example.ft_hangouts

import android.app.Activity
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
    private lateinit var typingIndicator: TextView

    private lateinit var AUTO_RESPONSES: List<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        AUTO_RESPONSES = listOf(
            getString(R.string.auto_response_1),
            getString(R.string.auto_response_2),
            getString(R.string.auto_response_3),
            getString(R.string.auto_response_4),
            getString(R.string.auto_response_5),
            getString(R.string.auto_response_6),
            getString(R.string.auto_response_7),
            getString(R.string.auto_response_8),
            getString(R.string.auto_response_9),
            getString(R.string.auto_response_10),
            getString(R.string.auto_response_11),
            getString(R.string.auto_response_12),
            getString(R.string.auto_response_13),
            getString(R.string.auto_response_14),
            getString(R.string.auto_response_15),
            getString(R.string.auto_response_16)
        )

        // Toolbar'ı ayarla
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        
        // Intent'ten verileri al
        contactId = intent.getIntExtra("contact_id", -1)
        contactName = intent.getStringExtra("contact_name") ?: getString(R.string.default_contact_name)
        
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
            typingIndicator = findViewById(R.id.typingIndicator)
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
        
        // Otomatik yanıt için gecikmeli bir işlem başlat
        scheduleAutoResponse()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            // Intent ile geriye contact_id değerini gönderelim
            val resultIntent = Intent()
            resultIntent.putExtra("contact_id", contactId)
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
    
    // Rastgele bir yanıt zamanlayıcısı
    private fun scheduleAutoResponse() {
        // 1-5 saniye arasında rastgele bir gecikme süresi
        val delay = (1000..5000).random().toLong()
        
        messageListView.post {
            typingIndicator.visibility = View.VISIBLE
        }
        
        // Bir süre sonra yanıt gönder
        messageListView.postDelayed({
            // Uygulama hala aktifse
            if (!isFinishing) {
                // "Yazıyor..." metnini gizle
                typingIndicator.visibility = View.GONE
                
                // Yanıt gönder
                sendAutoResponse()
            }
        }, delay)
    }
    
    // Otomatik yanıt gönderme
    private fun sendAutoResponse() {
        // Rastgele bir mesaj seç
        val responseMessage = AUTO_RESPONSES.random()
        val currentTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("contact_id", contactId)
            put("message", responseMessage)
            put("sender", "other")  // Karşı tarafın mesajı
            put("timestamp", currentTime)
        }
        
        db.insert("messages", null, values)
        
        // Mesajları yeniden yükle
        loadMessages()
        
        // Yazıyor animasyonu eklenebilir (opsiyonel)
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