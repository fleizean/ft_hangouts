package com.example.ft_hangouts

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.*

class EnhancedContactAdapter(
    context: Context,
    private val contacts: List<ContactWithMessages>
) : ArrayAdapter<ContactWithMessages>(context, 0, contacts) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(
            R.layout.item_contact,
            parent,
            false
        )

        val contact = getItem(position)

        // View'ları bul
        val profileImage = view.findViewById<ImageView>(R.id.contactListImage)
        val nameText = view.findViewById<TextView>(R.id.contactListName)
        val lastMessageText = view.findViewById<TextView>(R.id.contactListLastMessage)
        val timeText = view.findViewById<TextView>(R.id.contactListTime)
        val unreadBadge = view.findViewById<TextView>(R.id.contactListUnreadBadge)

        contact?.let { c ->
            // İsim
            nameText.text = c.name

            // Profil fotoğrafı
            if (!c.image.isNullOrEmpty()) {
                try {
                    val imageUri = Uri.parse(c.image)
                    profileImage.setImageURI(imageUri)
                } catch (e: Exception) {
                    profileImage.setImageResource(R.drawable.default_profile)
                }
            } else {
                profileImage.setImageResource(R.drawable.default_profile)
            }

            // Son mesaj
            if (c.lastMessage.isNotEmpty()) {
                lastMessageText.text = c.lastMessage
                lastMessageText.visibility = View.VISIBLE

                // Zaman formatı
                timeText.text = formatTime(c.lastMessageTime)
                timeText.visibility = View.VISIBLE
            } else {
                lastMessageText.text = context.getString(R.string.no_messages_yet)
                lastMessageText.visibility = View.VISIBLE
                timeText.visibility = View.GONE
            }

            // Okunmamış mesaj sayısı
            if (c.unreadCount > 0) {
                unreadBadge.text = c.unreadCount.toString()
                unreadBadge.visibility = View.VISIBLE
            } else {
                unreadBadge.visibility = View.GONE
            }
        }

        return view
    }

    private fun formatTime(timestamp: String): String {
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val messageDate = sdf.parse(timestamp) ?: return ""

            val now = Calendar.getInstance()
            val messageCalendar = Calendar.getInstance().apply { time = messageDate }

            return when {
                // Bugün
                now.get(Calendar.YEAR) == messageCalendar.get(Calendar.YEAR) &&
                        now.get(Calendar.DAY_OF_YEAR) == messageCalendar.get(Calendar.DAY_OF_YEAR) -> {
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(messageDate)
                }
                // Bu hafta
                now.get(Calendar.YEAR) == messageCalendar.get(Calendar.YEAR) &&
                        now.get(Calendar.WEEK_OF_YEAR) == messageCalendar.get(Calendar.WEEK_OF_YEAR) -> {
                    SimpleDateFormat("EEEE", Locale.getDefault()).format(messageDate)
                }
                // Bu yıl
                now.get(Calendar.YEAR) == messageCalendar.get(Calendar.YEAR) -> {
                    SimpleDateFormat("dd/MM", Locale.getDefault()).format(messageDate)
                }
                // Geçen yıl
                else -> {
                    SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(messageDate)
                }
            }
        } catch (e: Exception) {
            return ""
        }
    }
}

/**
 * Mesaj bilgileri ile zenginleştirilmiş kişi modeli
 */
data class ContactWithMessages(
    val id: Int,
    val name: String,
    val phone: String,
    val email: String?,
    val address: String?,
    val notes: String?,
    val image: String?,
    val lastMessage: String = "",
    val lastMessageTime: String = "",
    val unreadCount: Int = 0,
    val totalMessageCount: Int = 0
)