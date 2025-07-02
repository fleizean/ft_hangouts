package com.example.hangly

import android.util.Log

/**
 * SMS gönderici adreslerini işlemek için ortak utility sınıfı
 * Bu sınıf hem SmsReceiver hem de SmsReader tarafından kullanılır
 */
object SenderAddressHandler {

    private const val TAG = "SenderAddressHandler"

    enum class SenderType {
        PHONE_NUMBER,    // Normal telefon numarası
        ALPHANUMERIC,    // Kurumsal/alfanümerik gönderici
        OTHER,           // Diğer formatlar
        UNKNOWN          // Bilinmeyen
    }

    /**
     * Gönderici adresinin türünü belirler
     */
    fun getSenderType(address: String?): SenderType {
        if (address.isNullOrBlank()) return SenderType.UNKNOWN

        return when {
            // Sadece rakam ve +, - karakterleri içeriyorsa telefon numarası
            address.matches(Regex("^[+\\-0-9\\s()]+$")) -> SenderType.PHONE_NUMBER
            // Harf içeriyorsa alfanümerik (kurumsal)
            address.matches(Regex(".*[a-zA-Z].*")) -> SenderType.ALPHANUMERIC
            // Diğer durumlar
            else -> SenderType.OTHER
        }
    }

    /**
     * Kurumsal gönderici için kişi adı oluşturur
     * ÖNEMLI: Bu eşleştirmeler hem bildirimde hem de veritabanında tutarlı olmalı
     */
    fun createCorporateName(address: String): String {
        // Önce tam adresi kontrol et, sonra kısmi eşleşmeleri
        val upperAddress = address.uppercase().trim()

        val corporateName = when {
            // Tam eşleşmeler önce (en spesifikten genel olanına)
            upperAddress == "BANKKART" -> "BankKart"
            upperAddress == "SAMSUNG" -> "Samsung Türkiye"
            upperAddress == "TEB" -> "TEB Bankası"
            upperAddress == "ZIRAAT" -> "Ziraat Bankası"
            upperAddress == "HALKBANK" -> "Halkbank"
            upperAddress == "ISBANK" -> "İş Bankası"
            upperAddress == "GARANTI" -> "Garanti BBVA"
            upperAddress == "AKBANK" -> "Akbank"
            upperAddress == "VAKIFBANK" -> "Vakıfbank"
            upperAddress == "YAPI" -> "Yapı Kredi"
            upperAddress == "FINANSBANK" -> "QNB Finansbank"
            upperAddress == "TURKCELL" -> "Turkcell"
            upperAddress == "VODAFONE" -> "Vodafone"
            upperAddress == "BIMCELL" || upperAddress == "BİMCELL" -> "BiP"

            // Kısmi eşleşmeler (adres içinde geçiyorsa)
            upperAddress.contains("BANKKART") -> "BankKart"
            upperAddress.contains("SAMSUNG") -> "Samsung Türkiye"
            upperAddress.contains("TEB") && !upperAddress.contains("TEBRIKS") -> "TEB Bankası"
            upperAddress.contains("ZIRAAT") -> "Ziraat Bankası"
            upperAddress.contains("HALKBANK") || upperAddress.contains("HALK BANK") -> "Halkbank"
            upperAddress.contains("ISBANK") || upperAddress.contains("İŞBANK") -> "İş Bankası"
            upperAddress.contains("GARANTI") -> "Garanti BBVA"
            upperAddress.contains("AKBANK") -> "Akbank"
            upperAddress.contains("VAKIFBANK") || upperAddress.contains("VAKIF BANK") -> "Vakıfbank"
            upperAddress.contains("YAPI") && upperAddress.contains("KREDI") -> "Yapı Kredi"
            upperAddress.contains("FINANSBANK") -> "QNB Finansbank"
            upperAddress.contains("TURKCELL") -> "Turkcell"
            upperAddress.contains("VODAFONE") -> "Vodafone"

            // Bilinmeyen kurumlar için orijinal adres (büyük harfle)
            else -> upperAddress
        }

        Log.d(TAG, "Corporate mapping: '$address' -> '$corporateName'")
        return corporateName
    }

    /**
     * Alfanümerik gönderici için telefon numarası placeholder'ı
     */
    fun getPlaceholderPhone(address: String): String {
        return "CORP_${address.uppercase().trim()}"
    }

    /**
     * Bildirim için görüntüleme adını formatla
     */
    fun getDisplayNameForNotification(address: String): String {
        val senderType = getSenderType(address)

        return when (senderType) {
            SenderType.ALPHANUMERIC -> createCorporateName(address)
            SenderType.PHONE_NUMBER -> formatPhoneForDisplay(address)
            else -> address.uppercase()
        }
    }

    /**
     * Telefon numarasını güzel format için düzenle
     */
    fun formatPhoneForDisplay(phoneNumber: String): String {
        val normalized = PhoneNumberMatcher.normalizePhoneNumber(phoneNumber)

        return when (normalized.length) {
            10 -> {
                // 5551234567 -> 0555 123 45 67
                "0${normalized.substring(0,3)} ${normalized.substring(3,6)} ${normalized.substring(6,8)} ${normalized.substring(8)}"
            }
            else -> phoneNumber
        }
    }

    /**
     * Test için - adres eşleştirmelerini kontrol et
     */
    fun testAddressMapping() {
        val testAddresses = listOf(
            "BANKKART",
            "bankkart",
            "SAMSUNG",
            "TEB",
            "AKBANK-INFO",
            "info-GARANTI",
            "+905551234567",
            "05551234567",
            "UnknownSender"
        )

        testAddresses.forEach { address ->
            val type = getSenderType(address)
            val displayName = getDisplayNameForNotification(address)
            val placeholder = if (type == SenderType.ALPHANUMERIC) getPlaceholderPhone(address) else "N/A"

            Log.d(TAG, "Test: '$address' -> Type: $type, Display: '$displayName', Placeholder: '$placeholder'")
        }
    }
}