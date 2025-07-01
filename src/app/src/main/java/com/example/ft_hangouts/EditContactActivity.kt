package com.example.hangly

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EditContactActivity : BaseActivity() {

    private lateinit var nameInput: EditText
    private lateinit var phoneInput: EditText
    private lateinit var emailInput: EditText
    private lateinit var addressInput: EditText
    private lateinit var notesInput: EditText
    private lateinit var dbHelper: DBHelper
    private var contactId = -1

    private val REQUIRED_PERMISSIONS =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // Android 10+ (API 29+) için
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        } else {
            // Android 7-9 için
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.CAMERA
            )
        }

    private var imageUri: Uri? = null
    private lateinit var imageView: ImageView
    private lateinit var currentPhotoPath: String
    private var photoFile: File? = null
    
    // Activity result launchers
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var galleryLauncher: ActivityResultLauncher<String>
    private lateinit var cameraLauncher: ActivityResultLauncher<Uri>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_contact)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        applyToolbarColor(toolbar)

        nameInput = findViewById(R.id.editName)
        phoneInput = findViewById(R.id.editPhone)
        emailInput = findViewById(R.id.editEmail)
        addressInput = findViewById(R.id.editAddress)
        notesInput = findViewById(R.id.editNotes)
        imageView = findViewById(R.id.profileImage)

        dbHelper = DBHelper(this)
        
        // Set up permission launchers
        setupPermissions()

        // Kişi ID'sini al
        contactId = intent.getIntExtra("contact_id", -1)
        
        if (contactId == -1) {
            Toast.makeText(this, getString(R.string.contact_not_found), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Mevcut kişi bilgilerini yükle
        loadContactData(contactId)

        val selectImageButton = findViewById<Button>(R.id.buttonSelectImage)
            
        selectImageButton.setOnClickListener {
            checkPermissionsAndShowSourceDialog()
        }

        val saveButton = findViewById<Button>(R.id.buttonSaveEdit)
        saveButton.setOnClickListener {
            updateContact()
        }
    }

    private fun setupPermissions() {
        // İzin isteme için launcher oluştur
        permissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
                val cameraPermissionGranted = permissions[Manifest.permission.CAMERA] ?: false
                val readPermissionGranted =
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        true // Android 10+ için storage izinleri farklı şekilde işlenir
                    } else {
                        permissions[Manifest.permission.READ_EXTERNAL_STORAGE] ?: false
                    }

                Log.d("PermissionDebug", "Camera permission: $cameraPermissionGranted")
                Log.d("PermissionDebug", "Storage permission: $readPermissionGranted")

                if (cameraPermissionGranted && readPermissionGranted) {
                    // İzinler tamam, seçenekleri göster
                    showImageSourceOptionsDialog()
                } else {
                    // İzinler eksik, kullanıcıyı bilgilendir
                    Toast.makeText(
                        this,
                        getString(R.string.permissions_required_photo),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

        // Galeri launcher'ını güncelleyin
        galleryLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                Log.d("GalleryDebug", "Gallery result URI: $uri")
                uri?.let {
                    try {
                        // Seçilen resmi uygulama depolamasına kopyala
                        val savedUri = saveImageToInternalStorage(uri)
                        savedUri?.let {
                            imageUri = savedUri
                            imageView.setImageURI(savedUri)
                            Log.d("GalleryDebug", "Image saved to: $savedUri")
                        } ?: run {
                            Toast.makeText(this, getString(R.string.image_save_failed), Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Log.e("GalleryDebug", "Error with image: ${e.message}", e)
                        Toast.makeText(this, getString(R.string.image_process_error), Toast.LENGTH_SHORT).show()
                    }
                }
            }

        // Kamera launcher'ını güncelleyin
        cameraLauncher =
            registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
                Log.d("CameraDebug", "Camera result success: $success, URI: $imageUri")
                if (success && imageUri != null) {
                    try {
                        imageView.setImageURI(imageUri)
                    } catch (e: Exception) {
                        Log.e("CameraDebug", "Error displaying image: ${e.message}", e)
                        Toast.makeText(this, getString(R.string.image_display_error), Toast.LENGTH_SHORT).show()
                    }
                }
            }
    }

    private fun loadContactData(id: Int) {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM contacts WHERE id = ?", arrayOf(id.toString()))
        
        if (cursor.moveToFirst()) {
            nameInput.setText(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            phoneInput.setText(cursor.getString(cursor.getColumnIndexOrThrow("phone")))
            emailInput.setText(cursor.getString(cursor.getColumnIndexOrThrow("email")))
            addressInput.setText(cursor.getString(cursor.getColumnIndexOrThrow("address")))
            notesInput.setText(cursor.getString(cursor.getColumnIndexOrThrow("notes")))
            
            // Fotoğrafı yükle
            try {
                val imageUriString = cursor.getString(cursor.getColumnIndexOrThrow("image"))
                if (!imageUriString.isNullOrEmpty()) {
                    imageUri = Uri.parse(imageUriString)
                    imageView.setImageURI(imageUri)
                }
            } catch (e: Exception) {
                // İmage sütunu henüz eklenmemiş olabilir
            }
        } else {
            Toast.makeText(this, getString(R.string.contact_not_found), Toast.LENGTH_SHORT).show()
            finish()
        }
        cursor.close()
    }

    private fun updateContact() {
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, getString(R.string.name_cannot_be_empty), Toast.LENGTH_SHORT).show()
            return
        }
        
        val phone = phoneInput.text.toString().trim()
        val email = emailInput.text.toString().trim()
        val address = addressInput.text.toString().trim()
        val notes = notesInput.text.toString().trim()
        
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("name", name)
            put("phone", phone)
            put("email", email)
            put("address", address)
            put("notes", notes)
            
            // Fotoğraf URI'sini ekle
            imageUri?.let { uri ->
                put("image", uri.toString())
                Log.d("ImageDebug", "Saving image URI: $uri")
            }
        }
        
        val updated = db.update("contacts", values, "id = ?", arrayOf(contactId.toString()))
        
        if (updated > 0) {
            Toast.makeText(this, getString(R.string.contact_updated), Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        } else {
            Toast.makeText(this, getString(R.string.error), Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermissionsAndShowSourceDialog() {
        Log.d("PermissionDebug", "Checking permissions")

        val hasStoragePermission =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                true // Android 10+ için farklı bir yapı kullanılacak
            } else {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            }

        val hasCameraPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        Log.d("PermissionDebug", "Storage permission: $hasStoragePermission")
        Log.d("PermissionDebug", "Camera permission: $hasCameraPermission")

        if (hasStoragePermission && hasCameraPermission) {
            // İzinler tamam, seçenekleri göster
            showImageSourceOptionsDialog()
        } else {
            // İzinler eksik, mantığını açıkla ve iste
            requestPermissions()
        }
    }

    private fun shouldShowPermissionRationale(): Boolean {
        return ActivityCompat.shouldShowRequestPermissionRationale(
            this,
            Manifest.permission.CAMERA
        ) ||
                ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                )
    }

    private fun requestPermissions() {
        Log.d("PermissionDebug", "Requesting permissions")
        permissionLauncher.launch(REQUIRED_PERMISSIONS)
    }
    
    private fun showSettingsDialog() {
        AlertDialog.Builder(this)
           .setTitle(getString(R.string.permissions_dialog_title))
            .setMessage(getString(R.string.permissions_dialog_message))
            .setPositiveButton(getString(R.string.go_to_settings)) { _, _ ->
                // Uygulama izin ayarlarına yönlendir
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showImageSourceOptionsDialog() {
        val options = arrayOf(getString(R.string.pick_from_gallery), getString(R.string.take_photo))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.select_image_source))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openGallery()
                    1 -> openCamera()
                }
            }
            .show()
    }

    private fun openGallery() {
        galleryLauncher.launch("image/*")
    }

    private fun createPhotoFile(): File {
        // Zaman damgası içeren benzersiz bir dosya adı oluştur
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "JPEG_" + timeStamp + "_"

        // Uygulamanın private dosyalar dizini
        val storageDir = filesDir

        return File.createTempFile(
            imageFileName,  // Önek
            ".jpg",         // Uzantı
            storageDir      // Dizin
        ).apply {
            // Dosya yolunu kaydet
            currentPhotoPath = absolutePath
        }
    }

    private fun openCamera() {
        try {
            val photoFile = createPhotoFile()
            photoFile.also {
                imageUri = FileProvider.getUriForFile(
                    this,
                    "${applicationContext.packageName}.fileprovider",
                    it
                )
                Log.d("CameraDebug", "Opening camera with URI: $imageUri")
                cameraLauncher.launch(imageUri)
            }
        } catch (e: Exception) {
            Log.e("CameraDebug", "Error creating photo file: ${e.message}", e)
            Toast.makeText(this, getString(R.string.camera_failed, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveImageToInternalStorage(uri: Uri): Uri? {
        return try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val imageFileName = "CONTACT_" + timeStamp + ".jpg"

            // Dahili dosya oluştur
            val outputFile = File(filesDir, imageFileName)

            // URI'den gelen veriyi kopyala
            contentResolver.openInputStream(uri)?.use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // FileProvider ile erişilebilir URI oluştur
            FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                outputFile
            )
        } catch (e: Exception) {
            Log.e("ImageDebug", "Error copying image: ${e.message}", e)
            null
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