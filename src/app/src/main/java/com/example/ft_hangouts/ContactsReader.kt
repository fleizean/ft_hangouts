package com.example.hangly

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import android.content.ContentValues
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class ContactsReader(private val context: Context) {

    companion object {
        private const val TAG = "ContactsReader"
    }

    private val dbHelper = DBHelper(context)

    fun hasContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Sistem rehberinden tüm kişileri çeker ve hangly veritabanına aktarır
     */
    fun importSystemContacts(): ImportResult {
        if (!hasContactsPermission()) {
            return ImportResult(false, 0, context.getString(R.string.contacts_permission_denied))
        }

        var importedCount = 0
        var skippedCount = 0

        try {
            val contacts = readSystemContacts()
            val db = dbHelper.writableDatabase

            for (contact in contacts) {
                // Bu kişi zaten var mı kontrol et
                if (!contactExists(db, contact)) {
                    // Kişiyi veritabanına ekle
                    val success = insertContact(db, contact)
                    if (success) {
                        importedCount++
                        Log.d(TAG, "Imported contact: ${contact.name}")
                    } else {
                        skippedCount++
                    }
                } else {
                    skippedCount++
                    Log.d(TAG, "Contact already exists: ${contact.name}")
                }
            }

            return ImportResult(
                true,
                importedCount,
                context.getString(R.string.contacts_import_success, importedCount, skippedCount)
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error importing contacts: ${e.message}")
            return ImportResult(false, 0, context.getString(R.string.contacts_import_error, e.message))
        }
    }

    /**
     * Sistem rehberinden kişileri okur - fotoğraf desteği eklendi
     */
    private fun readSystemContacts(): List<SystemContact> {
        val contacts = mutableListOf<SystemContact>()
        val contentResolver = context.contentResolver

        // Ana kişi bilgileri
        val cursor = contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.Contacts.HAS_PHONE_NUMBER,
                ContactsContract.Contacts.PHOTO_URI,
                ContactsContract.Contacts.PHOTO_THUMBNAIL_URI
            ),
            null,
            null,
            ContactsContract.Contacts.DISPLAY_NAME + " ASC"
        )

        cursor?.use { c ->
            while (c.moveToNext()) {
                val contactId = c.getString(c.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                val name = c.getString(c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME))
                val hasPhoneNumber = c.getInt(c.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER))

                // Fotoğraf URI'sini al
                val photoUriString = c.getString(c.getColumnIndexOrThrow(ContactsContract.Contacts.PHOTO_URI))
                val photoThumbnailUriString = c.getString(c.getColumnIndexOrThrow(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI))

                if (hasPhoneNumber > 0 && !name.isNullOrBlank()) {
                    // Telefon numaralarını al
                    val phones = getPhoneNumbers(contentResolver, contactId)
                    val emails = getEmails(contentResolver, contactId)

                    // Fotoğrafı işle ve dahili depolamaya kaydet
                    val savedPhotoUri = processContactPhoto(photoUriString, photoThumbnailUriString, name)

                    if (phones.isNotEmpty()) {
                        contacts.add(SystemContact(
                            name = name,
                            phone = phones.first(), // İlk telefon numarasını al
                            email = emails.firstOrNull() ?: "",
                            allPhones = phones,
                            allEmails = emails,
                            photoUri = savedPhotoUri // Kaydedilen fotoğraf URI'si
                        ))
                    }
                }
            }
        }

        Log.d(TAG, "Read ${contacts.size} contacts from system")
        return contacts
    }

    /**
     * Kişi fotoğrafını işler ve dahili depolamaya kaydeder
     */
    private fun processContactPhoto(photoUriString: String?, photoThumbnailUriString: String?, contactName: String): String? {
        // Önce büyük fotoğrafı dene, yoksa thumbnail'i kullan
        val sourceUriString = photoUriString ?: photoThumbnailUriString

        if (sourceUriString.isNullOrEmpty()) {
            Log.d(TAG, "No photo found for contact: $contactName")
            return null
        }

        return try {
            val sourceUri = Uri.parse(sourceUriString)
            saveContactPhotoToInternalStorage(sourceUri, contactName)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing photo for $contactName: ${e.message}")
            null
        }
    }

    /**
     * Kişi fotoğrafını dahili depolamaya kaydeder
     */
    private fun saveContactPhotoToInternalStorage(sourceUri: Uri, contactName: String): String? {
        return try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val safeName = contactName.replace(Regex("[^a-zA-Z0-9]"), "_")
            val imageFileName = "CONTACT_${safeName}_$timeStamp.jpg"

            // Dahili dosya oluştur
            val outputFile = File(context.filesDir, imageFileName)

            // Fotoğrafı kopyala
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(outputFile).use { output ->
                    input.copyTo(output)
                }
            }

            // FileProvider ile erişilebilir URI oluştur
            val savedUri = FileProvider.getUriForFile(
                context,
                "${context.applicationContext.packageName}.fileprovider",
                outputFile
            )

            Log.d(TAG, "Contact photo saved: $imageFileName for $contactName")
            savedUri.toString()

        } catch (e: Exception) {
            Log.e(TAG, "Error saving contact photo for $contactName: ${e.message}")
            null
        }
    }

    /**
     * Kişinin telefon numaralarını getirir
     */
    private fun getPhoneNumbers(contentResolver: ContentResolver, contactId: String): List<String> {
        val phones = mutableListOf<String>()

        val phoneCursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
            arrayOf(contactId),
            null
        )

        phoneCursor?.use { pc ->
            while (pc.moveToNext()) {
                val phoneNumber = pc.getString(pc.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                if (!phoneNumber.isNullOrBlank()) {
                    phones.add(phoneNumber.trim())
                }
            }
        }

        return phones
    }

    /**
     * Kişinin email adreslerini getirir
     */
    private fun getEmails(contentResolver: ContentResolver, contactId: String): List<String> {
        val emails = mutableListOf<String>()

        val emailCursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
            ContactsContract.CommonDataKinds.Email.CONTACT_ID + " = ?",
            arrayOf(contactId),
            null
        )

        emailCursor?.use { ec ->
            while (ec.moveToNext()) {
                val email = ec.getString(ec.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS))
                if (!email.isNullOrBlank()) {
                    emails.add(email.trim())
                }
            }
        }

        return emails
    }

    /**
     * Kişinin zaten var olup olmadığını kontrol eder
     */
    private fun contactExists(db: android.database.sqlite.SQLiteDatabase, contact: SystemContact): Boolean {
        // İsim kontrolü
        var cursor = db.rawQuery("SELECT id FROM contacts WHERE name = ?", arrayOf(contact.name))
        if (cursor.count > 0) {
            cursor.close()
            return true
        }
        cursor.close()

        // Telefon numarası kontrolü (akıllı eşleştirme ile)
        val matchingContactId = PhoneNumberMatcher.findMatchingContactAdvanced(db, contact.phone)
        if (matchingContactId != null) {
            return true
        }

        return false
    }

    /**
     * Kişiyi veritabanına ekler - fotoğraf desteği eklendi
     */
    private fun insertContact(db: android.database.sqlite.SQLiteDatabase, contact: SystemContact): Boolean {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        val values = ContentValues().apply {
            put("name", contact.name)
            put("phone", contact.phone)
            put("email", contact.email)
            put("address", "") // Sistem rehberinden adres alamıyoruz (ek izin gerekir)
            put("notes", context.getString(R.string.imported_from_contacts, timestamp))

            // Fotoğraf URI'sini ekle
            contact.photoUri?.let { photoUri ->
                put("image", photoUri)
                Log.d(TAG, "Saving photo URI for ${contact.name}: $photoUri")
            }
        }

        val result = db.insert("contacts", null, values)
        return result != -1L
    }

    /**
     * Belirli bir kişiyi sistem rehberinden çeker
     */
    fun findContactByPhone(phoneNumber: String): SystemContact? {
        if (!hasContactsPermission()) return null

        val contentResolver = context.contentResolver
        val normalizedPhone = PhoneNumberMatcher.normalizePhoneNumber(phoneNumber)

        // Telefon numarasıyla kişi ara
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )

        val cursor = contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.PhoneLookup._ID,
                ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.NUMBER,
                ContactsContract.PhoneLookup.PHOTO_URI
            ),
            null,
            null,
            null
        )

        cursor?.use { c ->
            if (c.moveToFirst()) {
                val contactId = c.getString(c.getColumnIndexOrThrow(ContactsContract.PhoneLookup._ID))
                val name = c.getString(c.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
                val phone = c.getString(c.getColumnIndexOrThrow(ContactsContract.PhoneLookup.NUMBER))
                val photoUriString = c.getString(c.getColumnIndexOrThrow(ContactsContract.PhoneLookup.PHOTO_URI))

                val emails = getEmails(contentResolver, contactId)
                val savedPhotoUri = processContactPhoto(photoUriString, null, name)

                return SystemContact(
                    name = name,
                    phone = phone,
                    email = emails.firstOrNull() ?: "",
                    allPhones = listOf(phone),
                    allEmails = emails,
                    photoUri = savedPhotoUri
                )
            }
        }

        return null
    }
}

/**
 * Sistem rehberinden okunan kişi bilgisi - fotoğraf desteği eklendi
 */
data class SystemContact(
    val name: String,
    val phone: String,
    val email: String,
    val allPhones: List<String> = listOf(),
    val allEmails: List<String> = listOf(),
    val photoUri: String? = null // Fotoğraf URI'si eklendi
)