package com.example.hangly

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class SmsManagerHelper(private val context: Context) {

    companion object {
        private const val TAG = "SmsManagerHelper"
        private const val SMS_SENT = "SMS_SENT"
        private const val SMS_DELIVERED = "SMS_DELIVERED"
        private const val SMS_PERMISSION_REQUEST = 101
    }

    private val smsManager = SmsManager.getDefault()

    // SMS gönderme durumunu dinleyen receiver'lar
    private val sentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (resultCode) {
                Activity.RESULT_OK -> {
                    Log.d(TAG, "SMS sent successfully")
                    Toast.makeText(context, context.getString(R.string.sms_sent), Toast.LENGTH_SHORT).show()
                }
                SmsManager.RESULT_ERROR_GENERIC_FAILURE -> {
                    Log.e(TAG, "Generic failure")
                    Toast.makeText(context, context.getString(R.string.sms_send_error_generic), Toast.LENGTH_SHORT).show()
                }
                SmsManager.RESULT_ERROR_NO_SERVICE -> {
                    Log.e(TAG, "No service")
                    Toast.makeText(context, context.getString(R.string.sms_send_error_no_service), Toast.LENGTH_SHORT).show()
                }
                SmsManager.RESULT_ERROR_NULL_PDU -> {
                    Log.e(TAG, "Null PDU")
                    Toast.makeText(context, context.getString(R.string.sms_send_error_null_pdu), Toast.LENGTH_SHORT).show()
                }
                SmsManager.RESULT_ERROR_RADIO_OFF -> {
                    Log.e(TAG, "Radio off")
                    Toast.makeText(context, context.getString(R.string.sms_send_error_radio_off), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val deliveredReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (resultCode) {
                Activity.RESULT_OK -> {
                    Log.d(TAG, "SMS delivered")
                    Toast.makeText(context, context.getString(R.string.sms_delivered), Toast.LENGTH_SHORT).show()
                }
                Activity.RESULT_CANCELED -> {
                    Log.d(TAG, "SMS not delivered")
                    Toast.makeText(context, context.getString(R.string.sms_not_delivered), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun checkSmsPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECEIVE_SMS
                ) == PackageManager.PERMISSION_GRANTED
    }

    fun requestSmsPermissions(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS
            ),
            SMS_PERMISSION_REQUEST
        )
    }

    fun sendSms(phoneNumber: String, message: String): Boolean {
        if (!checkSmsPermissions()) {
            Log.e(TAG, "SMS permissions not granted")
            return false
        }

        try {
            // PendingIntent'ler oluştur
            val sentPI = PendingIntent.getBroadcast(
                context,
                0,
                Intent(SMS_SENT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val deliveredPI = PendingIntent.getBroadcast(
                context,
                0,
                Intent(SMS_DELIVERED),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Receiver'ları kaydet
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(sentReceiver, IntentFilter(SMS_SENT), Context.RECEIVER_NOT_EXPORTED)
                context.registerReceiver(deliveredReceiver, IntentFilter(SMS_DELIVERED), Context.RECEIVER_NOT_EXPORTED)
            }

            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                val sentIntents = ArrayList<PendingIntent>()
                val deliveredIntents = ArrayList<PendingIntent>()

                for (i in parts.indices) {
                    sentIntents.add(sentPI)
                    deliveredIntents.add(deliveredPI)
                }

                smsManager.sendMultipartTextMessage(
                    phoneNumber,
                    null,
                    parts,
                    sentIntents,
                    deliveredIntents
                )
            } else {
                smsManager.sendTextMessage(
                    phoneNumber,
                    null,
                    message,
                    sentPI,
                    deliveredPI
                )
            }

            Log.d(TAG, "SMS send request initiated")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Error sending SMS: ${e.message}")
            Toast.makeText(context, context.getString(R.string.sms_error, e.message), Toast.LENGTH_LONG).show()
            return false
        }
    }

    fun unregisterReceivers() {
        try {
            context.unregisterReceiver(sentReceiver)
            context.unregisterReceiver(deliveredReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering receivers: ${e.message}")
        }
    }
}