package com.example.hangly

import android.Manifest
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.ContentValues
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : BaseActivity() {
    private lateinit var listView: ListView
    private lateinit var dbHelper: DBHelper
    private lateinit var enhancedAdapter: EnhancedContactAdapter
    private val contactsWithMessages = mutableListOf<ContactWithMessages>()
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var toolbar: Toolbar
    private var lastBackgroundTime: Long = 0

    companion object {
        private const val REQUEST_NOTIFICATION_PERMISSION = 100
        private const val SMS_PERMISSION_REQUEST = 101
        private const val SMS_READ_PERMISSION_REQUEST = 102
        private const val CONTACTS_READ_PERMISSION_REQUEST = 103
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPreferences = getSharedPreferences("hangly_prefs", Context.MODE_PRIVATE)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        // Apply saved color
        applyToolbarColor(toolbar)

        // Display background time notification if applicable
        val lastBackgroundTime = sharedPreferences.getLong("last_background_time", 0)
        val timeNow = System.currentTimeMillis()

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
            val intent = Intent(this, AddContactActivity::class.java)
            startActivity(intent)
        }

        listView = findViewById(R.id.contactListView)
        dbHelper = DBHelper(this)

        // Android 13+ için bildirim izni kontrolü
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
            }
        }

        // Notification channel oluştur
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val name = getString(R.string.notification_channel_name)
            val descriptionText = getString(R.string.notification_channel_description)
            val importance = android.app.NotificationManager.IMPORTANCE_DEFAULT
            val channel = android.app.NotificationChannel("message_channel", name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        NotificationHelper.createNotificationChannel(this)

        // SMS izinlerini kontrol et
        checkSmsPermissions()

        setupContactList()
        loadContacts()
    }

    private fun checkSmsPermissions() {
        val smsManagerHelper = SmsManagerHelper(this)
        if (!smsManagerHelper.checkSmsPermissions()) {
            smsManagerHelper.requestSmsPermissions(this)
        }
    }

    private fun setupContactList() {
        enhancedAdapter = EnhancedContactAdapter(this, contactsWithMessages)
        listView.adapter = enhancedAdapter

        listView.setOnItemClickListener { _, _, position, _ ->
            val contact = contactsWithMessages[position]
            val intent = Intent(this, ContactDetailActivity::class.java)
            intent.putExtra("contact_id", contact.id)
            startActivity(intent)

            // Mesajları okundu olarak işaretle
            markMessagesAsRead(contact.id)
        }
    }

    private fun markMessagesAsRead(contactId: Int) {
        Thread {
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply {
                put("read_status", 1)
            }
            db.update("messages", values, "contact_id = ? AND sender = 'other' AND read_status = 0", arrayOf(contactId.toString()))

            // UI'ı güncelle
            runOnUiThread {
                loadContacts()
            }
        }.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            SMS_READ_PERMISSION_REQUEST -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    importExistingSms()
                } else {
                    Toast.makeText(this, "SMS okuma izni olmadan mevcut mesajları içe aktaramazsınız", Toast.LENGTH_LONG).show()
                }
            }
            CONTACTS_READ_PERMISSION_REQUEST -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    importSystemContacts()
                } else {
                    Toast.makeText(this, "Rehber okuma izni olmadan kişileri içe aktaramazsınız", Toast.LENGTH_LONG).show()
                }
            }
            SMS_PERMISSION_REQUEST -> {
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
                    Toast.makeText(this, "SMS izinleri olmadan mesajlaşma özelliği çalışmaz", Toast.LENGTH_LONG).show()
                }
            }
            REQUEST_NOTIFICATION_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Bildirim izni verildi", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        lastBackgroundTime = System.currentTimeMillis()
        sharedPreferences.edit().putLong("last_background_time", lastBackgroundTime).apply()
    }

    override fun onResume() {
        super.onResume()
        loadContacts()

        val prefs = getSharedPreferences("hangly_prefs", Context.MODE_PRIVATE)
        val lastTime = prefs.getLong("last_background_time", 0)
        val now = System.currentTimeMillis()

        if (lastTime > 0 && now - lastTime > 2000) {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val formatted = dateFormat.format(Date(lastTime))
            Toast.makeText(this, getString(R.string.last_background) + ": $formatted", Toast.LENGTH_LONG).show()

            prefs.edit().putLong("last_background_time", 0).apply()
        }
    }

    fun refreshContactList() {
        loadContacts()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)

        if (intent?.getBooleanExtra("refresh_contacts", false) == true) {
            loadContacts()
        }
    }

    private fun loadContacts() {
        contactsWithMessages.clear()

        val contacts = dbHelper.getAllContactsWithMessages()
        contactsWithMessages.addAll(contacts)

        enhancedAdapter.notifyDataSetChanged()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_change_color -> {
                showColorPickerDialog()
                true
            }
            R.id.action_import_sms -> {
                showImportSmsDialog()
                true
            }
            R.id.action_import_contacts -> {
                showImportContactsDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showImportContactsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Rehberden Kişileri İçe Aktar")
            .setMessage("Telefonunuzun rehberindeki kişileri uygulamaya aktarmak istiyor musunuz?")
            .setPositiveButton("Aktar") { _, _ ->
                importSystemContacts()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun importSystemContacts() {
        val contactsReader = ContactsReader(this)

        if (!contactsReader.hasContactsPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_CONTACTS),
                CONTACTS_READ_PERMISSION_REQUEST
            )
            return
        }

        val progressDialog = ProgressDialog(this).apply {
            setMessage("Rehber kişileri aktarılıyor...")
            setCancelable(false)
            show()
        }

        Thread {
            val result = contactsReader.importSystemContacts()

            runOnUiThread {
                progressDialog.dismiss()

                AlertDialog.Builder(this)
                    .setTitle(if (result.success) "Başarılı" else "Hata")
                    .setMessage(result.message)
                    .setPositiveButton("Tamam") { _, _ ->
                        if (result.success && result.importedCount > 0) {
                            loadContacts()
                        }
                    }
                    .show()
            }
        }.start()
    }

    private fun showImportSmsDialog() {
        AlertDialog.Builder(this)
            .setTitle("SMS'leri İçe Aktar")
            .setMessage("Telefonunuzdaki mevcut SMS'leri uygulamaya aktarmak istiyor musunuz? Bu işlem biraz zaman alabilir.")
            .setPositiveButton("Aktar") { _, _ ->
                importExistingSms()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun importExistingSms() {
        val smsReader = SmsReader(this)

        if (!smsReader.hasReadSmsPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_SMS),
                SMS_READ_PERMISSION_REQUEST
            )
            return
        }

        val progressDialog = ProgressDialog(this).apply {
            setMessage("SMS'ler aktarılıyor...")
            setCancelable(false)
            show()
        }

        Thread {
            val result = smsReader.importAllSms()

            runOnUiThread {
                progressDialog.dismiss()

                AlertDialog.Builder(this)
                    .setTitle(if (result.success) "Başarılı" else "Hata")
                    .setMessage(result.message)
                    .setPositiveButton("Tamam") { _, _ ->
                        if (result.success && result.importedCount > 0) {
                            loadContacts()
                        }
                    }
                    .show()
            }
        }.start()
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
                saveToolbarColor(colorResId)
                applyToolbarColor(toolbar)
            }
            .show()
    }
}

// EnhancedContactAdapter artık ayrı dosyada, bu eski adapter'ı kaldırdık