package com.example.hangly

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object NotificationHelper {

    private const val CHANNEL_ID = "sms_channel"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(
                CHANNEL_ID, 
                context.getString(R.string.notification_channel_name), 
                importance
            ).apply {
                description = context.getString(R.string.notification_channel_description)
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showSmsNotification(context: Context, senderName: String, messageBody: String) {
        // MainActivity'ye yönlendiren intent
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notification_sms_title, senderName))
            .setContentText(messageBody.take(50) + if (messageBody.length > 50) "..." else "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(messageBody))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        val notificationManager = NotificationManagerCompat.from(context)

        // Android 13+ için bildirim izni kontrolü
        if (hasNotificationPermission(context)) {
            try {
                notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
            } catch (e: SecurityException) {
                // İzin reddedilirse sessizce devam et
                android.util.Log.w("NotificationHelper", "Notification permission denied: ${e.message}")
            }
        }
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ için POST_NOTIFICATIONS izni kontrolü
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            // Android 13 altı için areNotificationsEnabled kontrolü
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }
}