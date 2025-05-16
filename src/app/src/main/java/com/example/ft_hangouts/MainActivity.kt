package com.example.ft_hangouts

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import android.widget.ListView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.os.Handler
import android.os.Looper
import android.content.ContentValues
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class MainActivity : BaseActivity() {
    private lateinit var listView: ListView
    private lateinit var dbHelper: DBHelper
    private lateinit var adapter: ArrayAdapter<String>
    private val contactList = mutableListOf<String>()
    private val contactIds = mutableListOf<Int>()
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var toolbar: Toolbar
    private val handler = Handler(Looper.getMainLooper())
    private var isSimulationActive = false
    private var lastBackgroundTime: Long = 0
    
    companion object {
        private const val REQUEST_NOTIFICATION_PERMISSION = 100
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        sharedPreferences = getSharedPreferences("ft_hangouts_prefs", Context.MODE_PRIVATE)
        
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        
        // Apply saved color
        applyToolbarColor(toolbar)
        
        // Display background time notification if applicable
        val lastBackgroundTime = sharedPreferences.getLong("last_background_time", 0)
        val timeNow = System.currentTimeMillis()
        
        // Only show if last background time was at least 1 second ago (to avoid showing on first launch)
        // And if we're not returning from a rotation/configuration change
        if (lastBackgroundTime > 0 && (timeNow - lastBackgroundTime > 1000) && savedInstanceState == null) {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val formattedDate = dateFormat.format(Date(lastBackgroundTime))
            Toast.makeText(
                this, 
                getString(R.string.last_background) + ": $formattedDate", 
                Toast.LENGTH_LONG
            ).show()
        }
        
        val addContactButton = findViewById<Button>(R.id.buttonAddContact)
            addContactButton.setOnClickListener {
                // AddContactActivity'ye git
                val intent = Intent(this, AddContactActivity::class.java)
                startActivity(intent)
            }
        listView = findViewById(R.id.contactListView)
        dbHelper = DBHelper(this)
        
        // Android 13 (Tiramisu) ve sonrası için bildirim izni kontrolü
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            // Check if we have notification permission
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // Request the permission
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
            }
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val name = getString(R.string.notification_channel_name)
            val descriptionText = getString(R.string.notification_channel_description)
            val importance = android.app.NotificationManager.IMPORTANCE_DEFAULT
            val channel = android.app.NotificationChannel("message_channel", name, importance).apply {
                description = descriptionText
            }
            // Kanalı sisteme kaydet
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
        
        setupContactList()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Aktivite yok edilirken simülasyonu durdur
        if (isSimulationActive) {
            stopMessageSimulation()
        }
    }

    private fun setupContactList() {
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, contactList)
        listView.adapter = adapter
        
        listView.setOnItemClickListener { _, _, position, _ ->
            val contactId = contactIds[position]
            val intent = Intent(this, ContactDetailActivity::class.java)
            intent.putExtra("contact_id", contactId)
            startActivity(intent)
        }
    }

    private val simulationRunnable = object : Runnable {
        override fun run() {
            if (isSimulationActive) {
                simulateIncomingMessage()
                
                // 1-5 dakika arasında rastgele bir süre sonra tekrar çalıştır
                val delayMinutes = (1..5).random()
                val delayMillis = delayMinutes * 60 * 1000L
                handler.postDelayed(this, delayMillis)
            }
        }
    }

    private fun startMessageSimulation() {
        if (!isSimulationActive) {
            isSimulationActive = true
            // İlk mesajı 5-20 saniye içinde gönder
            val initialDelay = (5..20).random() * 1000L
            handler.postDelayed(simulationRunnable, initialDelay)
            Toast.makeText(
                this,
                getString(R.string.simulation_started, initialDelay/1000),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun stopMessageSimulation() {
    if (isSimulationActive) {
            isSimulationActive = false
            handler.removeCallbacks(simulationRunnable)
            // Bu kısımda Toast.makeText eksik, sadece state değişiyor
            Toast.makeText(
                this,
                getString(R.string.simulation_stopped),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    
    private fun simulateIncomingMessage() {
        val unknownNumber = "+90" + (1000000000..9999999999).random()
        
        // Numara kişilerde var mı kontrol et
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM contacts WHERE phone = ?", arrayOf(unknownNumber))
        
        if (cursor.count == 0) {
            // Yeni kişi oluştur - telefon numarasını isim olarak kullan
            val contactValues = ContentValues().apply {
                put("name", unknownNumber)
                put("phone", unknownNumber)
                put("email", "")
                put("address", "")
                put("notes", getString(R.string.auto_created) + 
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            }
            
            val newContactId = db.insert("contacts", null, contactValues)
            
            // Rastgele mesaj metinleri
            val messages = listOf(
                getString(R.string.sim_message_1),
                getString(R.string.sim_message_2),
                getString(R.string.sim_message_3),
                getString(R.string.sim_message_4),
                getString(R.string.sim_message_5),
                getString(R.string.sim_message_6),
                getString(R.string.sim_message_7),
                getString(R.string.sim_message_8),
                getString(R.string.sim_message_9),
                getString(R.string.sim_message_10)
            )
            
            val randomMessage = messages.random()
            
            // Yeni mesaj ekle
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val messageValues = ContentValues().apply {
                put("contact_id", newContactId)
                put("message", randomMessage)
                put("sender", "other")
                put("timestamp", timestamp)
            }
            
            db.insert("messages", null, messageValues)
            
            // Bildirim göster - izin kontrolü ile
            showNotification(unknownNumber, randomMessage)
            
            // Kişi listesini güncelle
            loadContacts()
            
            // Kullanıcıyı bilgilendir
            Toast.makeText(
                this, 
                getString(R.string.new_message_received, unknownNumber), 
                Toast.LENGTH_LONG
            ).show()
        }
        
        cursor.close()
    }
    
    private fun showNotification(sender: String, message: String) {
        // Bildirim göster
        val builder = NotificationCompat.Builder(this, "message_channel")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_title, sender))
            .setContentText(message.take(30) + if(message.length > 30) "..." else "")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            
        val notificationManager = NotificationManagerCompat.from(this)
        
        // Android 13 ve üzeri için bildirim izni kontrolü
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU || 
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) 
            == PackageManager.PERMISSION_GRANTED) {
            
            notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Save current time when app goes to background
        lastBackgroundTime = System.currentTimeMillis()
        sharedPreferences.edit().putLong("last_background_time", lastBackgroundTime).apply()
    }
    
    override fun onResume() {
        super.onResume()
        loadContacts()

        val prefs = getSharedPreferences("ft_hangouts_prefs", Context.MODE_PRIVATE)
        val lastTime = prefs.getLong("last_background_time", 0)
        val now = System.currentTimeMillis()

        if (lastTime > 0 && now - lastTime > 2000) {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val formatted = dateFormat.format(Date(lastTime))
            Toast.makeText(this, getString(R.string.last_background) + ": $formatted", Toast.LENGTH_LONG).show()

            // Sıfırla ki tekrar gösterilmesin
            prefs.edit().putLong("last_background_time", 0).apply()
        }
    }

    fun refreshContactList() {
        loadContacts()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        
        // Yeni kişi eklendiğinde güncelleme isteği kontrol edilir
        if (intent?.getBooleanExtra("refresh_contacts", false) == true) {
            loadContacts()
        }
    }
    
    private fun loadContacts() {
        contactList.clear()
        contactIds.clear()
        
        val db = dbHelper.readableDatabase
        val cursor: Cursor = db.rawQuery("SELECT * FROM contacts ORDER BY name", null)
        
        while (cursor.moveToNext()) {
            val id = cursor.getInt(cursor.getColumnIndexOrThrow("id"))
            val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
            contactList.add(name)
            contactIds.add(id)
        }
        
        cursor.close()
        adapter.notifyDataSetChanged()
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        
        // Simülasyon durumuna göre menü öğesini güncelle
        menu.findItem(R.id.action_toggle_simulation)?.setTitle(
            if (isSimulationActive) R.string.stop_simulation else R.string.start_simulation
        )
        
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_change_color -> {
                showColorPickerDialog()
                true
            }
            R.id.action_toggle_simulation -> {
                // Simülasyon durumunu değiştir
                if (isSimulationActive) {
                    stopMessageSimulation()
                } else {
                    startMessageSimulation()
                }
                // Menü başlığını güncelle
                item.setTitle(if (isSimulationActive) R.string.stop_simulation else R.string.start_simulation)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun showColorPickerDialog() {
    val colors = arrayOf(
        getString(R.string.color_purple) to R.color.purple_500,
        getString(R.string.color_blue) to R.color.toolbar_blue,
        getString(R.string.color_green) to R.color.toolbar_green,
        getString(R.string.color_red) to R.color.toolbar_red
    )
    
    val colorNames = colors.map { it.first }.toTypedArray()
    
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.change_toolbar_color))
        .setItems(colorNames) { _, which ->
            val colorResId = colors[which].second
            saveToolbarColor(colorResId) // BaseActivity'deki metodu kullan
            applyToolbarColor(toolbar)   // BaseActivity'deki metodu kullan
        }
        .show()
}
    
    private fun saveAndApplyToolbarColor(colorResId: Int) {
        // Save color preference
        saveToolbarColor(colorResId)
        
        // Apply new color
        applyToolbarColor(toolbar)
    }
    
    // Override the method with 'open' modifier in BaseActivity
    
    
   
}

class ContactAdapter(context: Context, val contacts: List<Contact>) : 
    ArrayAdapter<Contact>(context, 0, contacts) {
    
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_contact, parent, false)
        
        val contact = getItem(position)
        val nameTextView = view.findViewById<TextView>(R.id.contactListName)
        val imageView = view.findViewById<ImageView>(R.id.contactListImage)
        
        nameTextView.text = contact?.name
        
        // Profil fotoğrafını göster (varsa)
        if (!contact?.image.isNullOrEmpty()) {
            try {
                val imageUri = Uri.parse(contact?.image)
                imageView.setImageURI(imageUri)
            } catch (e: Exception) {
                imageView.setImageResource(R.drawable.default_profile)
            }
        } else {
            imageView.setImageResource(R.drawable.default_profile)
        }
        
        return view
    }
}

// Contact sınıfını ekleyin
data class Contact(
    val id: Int,
    val name: String,
    val phone: String,
    val email: String?,
    val address: String?,
    val notes: String?,
    val image: String?
)
