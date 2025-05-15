package com.example.ft_hangouts

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.database.Cursor
import android.os.Bundle
import android.view.MenuItem
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import android.widget.Toast
import android.content.Intent
import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.widget.ImageView

class ContactDetailActivity : BaseActivity() {

    private lateinit var dbHelper: DBHelper
    private lateinit var editButton: Button
    private var contactId: Int = -1

    private val CALL_PERMISSION_REQUEST = 100
    private lateinit var phoneNumber: String
    
    // UI bileşenleri için değişkenler ekleyelim (daha sonra tekrar kullanmak için)
    private lateinit var nameView: TextView
    private lateinit var phoneView: TextView
    private lateinit var emailView: TextView
    private lateinit var addressView: TextView
    private lateinit var notesView: TextView
    
    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact_detail)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.contact_details_title)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        dbHelper = DBHelper(this)

        nameView = findViewById(R.id.detailName)
        phoneView = findViewById(R.id.detailPhone)
        emailView = findViewById(R.id.detailEmail)
        addressView = findViewById(R.id.detailAddress)
        notesView = findViewById(R.id.detailNotes)
        val deleteButton = findViewById<Button>(R.id.buttonDeleteContact)

        contactId = intent.getIntExtra("contact_id", -1)

        if (contactId != -1) {
            // Kişi detaylarını yükle
            loadContactDetails(contactId)
            
            deleteButton.setOnClickListener {
                showDeleteConfirmation()
            }

            val messageButton = findViewById<Button>(R.id.buttonMessage)
            messageButton.setOnClickListener {
                val intent = Intent(this, ChatActivity::class.java)
                intent.putExtra("contact_id", contactId)
                intent.putExtra("contact_name", nameView.text.toString())
                startActivity(intent)
            }

            editButton = findViewById(R.id.buttonEditContact)

            editButton.setOnClickListener {
                val intent = Intent(this, EditContactActivity::class.java)
                intent.putExtra("contact_id", contactId)
                startActivityForResult(intent, 1)
            }

            val callButton = findViewById<Button>(R.id.buttonCall)
            callButton.setOnClickListener {
                requestCallPermissionAndDial()
            }

        } else {
            nameView.text = getString(R.string.contact_not_found_message)
            deleteButton.isEnabled = false
        }
    }
    
    // Eksik olan loadContactDetails metodu
    private fun loadContactDetails(id: Int) {
        val db = dbHelper.readableDatabase
        val cursor: Cursor = db.rawQuery("SELECT * FROM contacts WHERE id = ?", arrayOf(id.toString()))
        
        if (cursor.moveToFirst()) {
            nameView.text = cursor.getString(cursor.getColumnIndexOrThrow("name"))
            // Telefon numarasını alıp değişkene atayalım (arama için)
            phoneNumber = cursor.getString(cursor.getColumnIndexOrThrow("phone"))
            phoneView.text = phoneNumber
            emailView.text = cursor.getString(cursor.getColumnIndexOrThrow("email"))
            addressView.text = cursor.getString(cursor.getColumnIndexOrThrow("address"))
            notesView.text = cursor.getString(cursor.getColumnIndexOrThrow("notes"))
            
            // Profil fotoğrafını göster (eğer 'image' sütunu varsa)
            try {
                val contactImage = findViewById<ImageView>(R.id.contactImage)
                val imageUri = cursor.getString(cursor.getColumnIndexOrThrow("image"))
                if (imageUri != null && imageUri.isNotEmpty()) {
                    contactImage.setImageURI(Uri.parse(imageUri))
                } else {
                    contactImage.setImageResource(R.drawable.default_profile)
                }
            } catch (e: Exception) {
                // Eğer image sütunu henüz eklenmemiş olabilir, hata mesajı göstermek yerine sessizce devam et
            }
        } else {
            // Kişi bulunamadı
            nameView.text = getString(R.string.contact_not_found_message)
        }
        cursor.close()
    }
    
    private fun showDeleteConfirmation() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_confirmation_title))
            .setMessage(getString(R.string.delete_confirmation_message))
            .setPositiveButton(getString(R.string.yes)) { _, _ ->
                deleteContact()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    private fun deleteContact() {
        if (contactId != -1) {
            val isDeleted = dbHelper.deleteContact(contactId)
            if (isDeleted) {
                Toast.makeText(this, getString(R.string.delete_success), Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, getString(R.string.delete_failure), Toast.LENGTH_SHORT).show()
            }
        }
    }

        // requestCallPermissionAndDial metodunu güncelleyin
    private fun requestCallPermissionAndDial() {
        try {
            // İzin kontrolü
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                // İzin verilmemişse iste
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CALL_PHONE)) {
                    // Kullanıcıya neden izin istediğimizi açıklayalım
                    AlertDialog.Builder(this)
                        .setTitle("Arama İzni Gerekli")
                        .setMessage("Kişiyi doğrudan aramak için telefon izni gerekiyor.")
                        .setPositiveButton("İzin Ver") { _, _ ->
                            ActivityCompat.requestPermissions(
                                this,
                                arrayOf(Manifest.permission.CALL_PHONE),
                                CALL_PERMISSION_REQUEST
                            )
                        }
                        .setNegativeButton("İptal", null)
                        .show()
                } else {
                    // İlk kez izin isteniyor
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.CALL_PHONE),
                        CALL_PERMISSION_REQUEST
                    )
                }
            } else {
                // İzin zaten verilmiş
                dialPhoneNumber()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "İzin isteği sırasında hata oluştu: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

        // dialPhoneNumber metodunu güncelleyin
    private fun dialPhoneNumber() {
        if (::phoneNumber.isInitialized && phoneNumber.isNotBlank()) {
            try {
                // Telefon numarasını temizle (boşluk ve özel karakterleri kaldır)
                val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
                
                // Intent oluştur
                val callIntent = Intent(Intent.ACTION_CALL)
                callIntent.data = Uri.parse("tel:$cleanNumber")
                
                // Intent çağrılabilir mi kontrol et
                if (callIntent.resolveActivity(packageManager) != null) {
                    startActivity(callIntent)
                } else {
                    Toast.makeText(this, "Arama yapabilecek uygulama bulunamadı", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Arama yapılamadı: ${e.message}", Toast.LENGTH_SHORT).show()
                e.printStackTrace()
            }
        } else {
            Toast.makeText(this, "Geçerli bir telefon numarası yok", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CALL_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                dialPhoneNumber()
            } else {
                Toast.makeText(this, "Arama izni reddedildi", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == Activity.RESULT_OK) {
            // Kişi güncellendi, bilgileri yeniden yüklüyoruz
            loadContactDetails(contactId)
        }
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}