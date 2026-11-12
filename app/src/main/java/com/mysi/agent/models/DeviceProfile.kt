package com.mysi.agent.models

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.storage.StorageManager
import java.io.File

/**
 * What the model advisor needs to know about this phone. Plain data, so every rule is tested on
 * the JVM with made-up phones. It is read on the phone, used on the phone and never sent anywhere.
 */
data class DeviceProfile(
    /** RAM the kernel manages ([ActivityManager.MemoryInfo.totalMem]); a little below the advertised size. */
    val totalRamBytes: Long,
    /** RAM as advertised ([ActivityManager.MemoryInfo.advertisedMem], API 34+); 0 when unknown. */
    val advertisedRamBytes: Long,
    /** RAM free right now ([ActivityManager.MemoryInfo.availMem]); changes as apps open and close. */
    val availableRamBytes: Long,
    /** The system says memory is low right now ([ActivityManager.MemoryInfo.lowMemory]). */
    val lowMemoryNow: Boolean,
    /** An Android Go style low-RAM phone ([ActivityManager.isLowRamDevice]). */
    val lowRamDevice: Boolean,
    /** Space the app may use where models are stored, including cache the system can clear. */
    val freeStorageBytes: Long,
    val socManufacturer: String,
    val socModel: String,
    val hardware: String,
    val abis: List<String>,
    val sdkInt: Int,
    /** On Wi-Fi or another network that is not charged by the megabyte. */
    val unmetered: Boolean,
    /** Any network with internet at all. */
    val online: Boolean,
) {
    /**
     * Phone memory in whole GB as people know it ("8 GB"). The advertised figure when Android
     * reports one; otherwise [totalRamBytes] rounded up to the next common size, since the kernel
     * always sees a little less than is fitted.
     */
    val ramGb: Int
        get() {
            if (advertisedRamBytes > 0) return ((advertisedRamBytes + Format.GIB / 2) / Format.GIB).toInt().coerceAtLeast(1)
            val gib = totalRamBytes.toDouble() / Format.GIB
            return COMMON_SIZES.firstOrNull { it >= gib } ?: kotlin.math.ceil(gib).toInt()
        }

    /** "Qualcomm SM8650", or "Unknown chip" when the phone does not say. */
    val chip: String
        get() {
            val maker = socManufacturer.trim().takeUnless { it.isEmpty() || it.equals("unknown", true) }
            val model = socModel.trim().takeUnless { it.isEmpty() || it.equals("unknown", true) }
            return listOfNotNull(maker, model).joinToString(" ").ifEmpty {
                hardware.trim().takeUnless { it.isEmpty() || it.equals("unknown", true) } ?: "Unknown chip"
            }
        }

    /** LiteRT-LM ships only 64-bit ARM and x86 libraries. */
    val supported64Bit: Boolean get() = abis.any { it == "arm64-v8a" || it == "x86_64" }

    companion object {
        /** Advertised RAM sizes phones ship with, in GB. */
        val COMMON_SIZES = listOf(1, 2, 3, 4, 6, 8, 10, 12, 16, 18, 20, 24, 32)
    }
}

/** Reads a [DeviceProfile] from Android. Needs no permission beyond ACCESS_NETWORK_STATE, which the app holds. */
object DeviceProfiles {
    fun read(context: Context, modelDir: File?): DeviceProfile {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        return DeviceProfile(
            totalRamBytes = mem.totalMem,
            advertisedRamBytes = mem.advertisedMem,
            availableRamBytes = mem.availMem,
            lowMemoryNow = mem.lowMemory,
            lowRamDevice = am.isLowRamDevice,
            freeStorageBytes = freeBytes(context, modelDir),
            socManufacturer = Build.SOC_MANUFACTURER,
            socModel = Build.SOC_MODEL,
            hardware = Build.HARDWARE,
            abis = Build.SUPPORTED_ABIS.toList(),
            sdkInt = Build.VERSION.SDK_INT,
            unmetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true,
            online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
        )
    }

    /**
     * Space for a new model where it will be saved, and in the app's internal storage (the
     * engine's cache lives there). On phones both are normally the same volume; the smaller figure
     * is used either way. [StorageManager.getAllocatableBytes] counts cached data the system would
     * clear on request, as Google recommends for large downloads.
     */
    fun freeBytes(context: Context, modelDir: File?): Long {
        val sm = context.getSystemService(StorageManager::class.java)
        fun free(dir: File): Long =
            runCatching { sm.getAllocatableBytes(sm.getUuidForPath(dir)) }.getOrElse { dir.usableSpace }
        val internal = free(context.filesDir)
        val external = modelDir?.let(::free) ?: return 0
        return minOf(internal, external)
    }

    /** The connection right now: unmetered (Wi-Fi) or not. */
    fun unmetered(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return runCatching { cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) }.getOrNull() == true
    }
}
