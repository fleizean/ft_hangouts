package com.example.ft_hangouts

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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

class MainActivity : BaseActivity() {

    private lateinit var listView: ListView
    private lateinit var dbHelper: DBHelper
    private lateinit var adapter: ArrayAdapter<String>
    private val contactList = mutableListOf<String>()
    private val contactIds = mutableListOf<Int>()
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var toolbar: Toolbar

    private var lastBackgroundTime: Long = 0
    
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
        
        listView = findViewById(R.id.contactListView)
        dbHelper = DBHelper(this)
        
        val addButton = findViewById<Button>(R.id.buttonAddContact)
        addButton.setOnClickListener {
            val intent = Intent(this, AddContactActivity::class.java)
            startActivity(intent)
        }
        
        setupContactList()
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
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_change_color -> {
                showColorPickerDialog()
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
                saveAndApplyToolbarColor(colorResId)
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
    override fun applyToolbarColor(toolbar: Toolbar) {
        // Call parent implementation which handles the actual color application
        super.applyToolbarColor(toolbar)
    }
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