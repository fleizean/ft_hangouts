package com.example.hangly

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import androidx.appcompat.widget.Toolbar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.os.Environment

class AddContactActivity : BaseActivity() {

    private lateinit var dbHelper: DBHelper

    private val PICK_IMAGE_REQUEST = 1
    private val CAPTURE_IMAGE_REQUEST = 2
    private var imageUri: Uri? = null
    private lateinit var imageView: ImageView
    
    // Class seviyesinde tanımla
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var galleryLauncher: ActivityResultLauncher<String>
    private lateinit var cameraLauncher: ActivityResultLauncher<Uri>
    private lateinit var currentPhotoPath: String

    private var photoFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_contact)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.add_contact_title)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        dbHelper = DBHelper(this)

        // İzin isteme mekanizmasını başlat
        setupPermissions()

        val nameInput = findViewById<EditText>(R.id.inputName)
        val phoneInput = findViewById<EditText>(R.id.inputPhone)
        val emailInput = findViewById<EditText>(R.id.inputEmail)
        val addressInput = findViewById<EditText>(R.id.inputAddress)
        val notesInput = findViewById<EditText>(R.id.inputNotes)
        imageView = findViewById(R.id.profileImage)
        val selectImageButton = findViewById<Button>(R.id.buttonSelectImage)
        selectImageButton.setOnClickListener {
            checkPermissionsAndShowSourceDialog()
        }

        val saveButton = findViewById<Button>(R.id.buttonSave)

        saveButton.setOnClickListener {
            val name = nameInput.text.toString()
            val phone = phoneInput.text.toString()
            val email = emailInput.text.toString()
            val address = addressInput.text.toString()
            val notes = notesInput.text.toString()

            if (name.isBlank()) {
                Toast.makeText(this, getString(R.string.name_cannot_be_empty), Toast.LENGTH_SHORT)
                    .show()
                return@setOnClickListener
            }

            if (phone.isBlank()) {
                Toast.makeText(this, getString(R.string.phone_cannot_be_empty), Toast.LENGTH_SHORT)
                    .show()
                return@setOnClickListener
            }

            val db = dbHelper.writableDatabase
            val values = ContentValues().apply {
                put("name", name)
                put("phone", phone)
                put("email", email)
                put("address", address)
                put("notes", notes)

                // Sadece URI'yi kaydet - artık dahili depolamada olduğu için izin gerekmiyor
                imageUri?.let { uri ->
                    put("image", uri.toString())
                    Log.d("ImageDebug", "Saving image URI: $uri")
                }
            }

            val newRowId = db.insert("contacts", null, values)

            if (newRowId != -1L) {
                Toast.makeText(this, getString(R.string.contact_added_success), Toast.LENGTH_SHORT)
                    .show()
                setResult(Activity.RESULT_OK)
                finish()
            } else {
                Toast.makeText(this, getString(R.string.contact_add_error), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Tüm kalıcı URI izinleri kaldırıldı
        // Dahili depolamaya kaydedilen dosyalar için izne gerek yok
    }

    private val REQUIRED_PERMISSIONS = when {
    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
        // Android 14+ (API 34+) için
        arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.READ_MEDIA_IMAGES
        )
    }
    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU -> {
        // Android 13 (API 33) için
        arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.READ_MEDIA_IMAGES
        )
    }
    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q -> {
        // Android 10-12 (API 29-32) için
        arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
    }
    else -> {
        // Android 9 ve altı (API 28-) için
        arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }
}


    private fun setupPermissions() {
    // İzin isteme için launcher oluştur
    permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val cameraPermissionGranted = permissions[Manifest.permission.CAMERA] ?: false
            
            // Storage izni kontrolü - API seviyesine göre
            val storagePermissionGranted = when {
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU -> {
                    permissions[Manifest.permission.READ_MEDIA_IMAGES] ?: false
                }
                else -> {
                    permissions[Manifest.permission.READ_EXTERNAL_STORAGE] ?: false
                }
            }

            Log.d("PermissionDebug", "Camera: $cameraPermissionGranted, Storage: $storagePermissionGranted")

            if (cameraPermissionGranted && storagePermissionGranted) {
                showImageSourceOptionsDialog()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.permissions_required),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    // Galeri launcher - Scoped Storage uyumlu
    galleryLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            Log.d("GalleryDebug", "Gallery result URI: $uri")
            uri?.let {
                try {
                    // URI'ye kalıcı izin ver (Android 10+)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        try {
                            contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (e: SecurityException) {
                            Log.w("GalleryDebug", "Could not take persistable permission: ${e.message}")
                            // Kalıcı izin alınamasa bile devam et
                        }
                    }

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

    // Kamera launcher - FileProvider uyumlu
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

private fun checkPermissionsAndShowSourceDialog() {
    Log.d("PermissionDebug", "Checking permissions for API ${android.os.Build.VERSION.SDK_INT}")

    // API seviyesine göre storage izni kontrolü
    val hasStoragePermission = when {
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU -> {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        }
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q -> {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
        else -> {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    val hasCameraPermission = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    Log.d("PermissionDebug", "Storage permission: $hasStoragePermission")
    Log.d("PermissionDebug", "Camera permission: $hasCameraPermission")

    if (hasStoragePermission && hasCameraPermission) {
        showImageSourceOptionsDialog()
    } else {
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
    Log.d("PermissionDebug", "Requesting permissions: ${REQUIRED_PERMISSIONS.joinToString()}")
    permissionLauncher.launch(REQUIRED_PERMISSIONS)
}

    private fun createPhotoFile(): File {
        // Zaman damgası içeren benzersiz bir dosya adı oluştur
        val timeStamp =
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
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

    private fun showSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.permissions_dialog_title))
            .setMessage(getString(R.string.permissions_dialog_message))
            .setPositiveButton(getString(R.string.go_to_settings)) { _, _ ->
                // Uygulama izin ayarlarına yönlendir
                val intent =
                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showImageSourceOptionsDialog() {
        val options =
            arrayOf(getString(R.string.pick_from_gallery), getString(R.string.take_photo))
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
            val timeStamp =
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
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