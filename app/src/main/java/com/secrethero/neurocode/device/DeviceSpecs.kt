package com.secrethero.neurocode.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build

data class DeviceSnapshot(
    val abi: DeviceAbi,
    val supportedAbis: List<String>,
    val cores: Int,
    val totalMemoryMb: Long,
    val availableMemoryMb: Long,
    val reportedLowRam: Boolean,
) {
    fun recommendation(): ModelRecommendation = DeviceProfile.recommendModel(
        totalMemoryMb = totalMemoryMb,
        cores = cores,
        abi = abi,
        reportedLowRam = reportedLowRam,
    )
}

/**
 * Reads real device capabilities (ABI list, CPU count, RAM, low-RAM flag).
 */
class DeviceSpecs(private val context: Context) {

    fun snapshot(): DeviceSnapshot {
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)
        val totalMb = memoryInfo.totalMem / DeviceProfile.MB
        val availableMb = memoryInfo.availMem / DeviceProfile.MB
        return DeviceSnapshot(
            abi = DeviceAbi.fromSupportedAbis(Build.SUPPORTED_ABIS.toList()),
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            totalMemoryMb = totalMb,
            availableMemoryMb = availableMb,
            reportedLowRam = activityManager?.isLowRamDevice ?: false,
        )
    }
}
