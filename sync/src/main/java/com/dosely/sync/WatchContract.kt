package com.dosely.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

object WatchContract {
    const val SNAPSHOT = "/dosely/v1/snapshot"
    const val EVENT = "/dosely/v1/event/"
    const val ACK = "/dosely/v1/ack/"
    const val PAYLOAD = "payload"
    val json = Json { ignoreUnknownKeys = true }
    val sites = listOf("Abdomen · left", "Abdomen · right", "Thigh · left", "Thigh · right", "Upper arm · left", "Upper arm · right")
}

@Serializable
data class WatchSnapshot(
    val version: Int = 1,
    val onboarded: Boolean = false,
    val medId: String = "",
    val medName: String = "",
    val doses: List<Double> = emptyList(),
    val lastDoseMg: Double? = null,
    val lastSite: String = "",
    val nextDose: String = "",
    val weightGrams: Int = 0,
    val imperial: Boolean = false,
    val updatedAt: Long = 0,
)

@Serializable
data class WatchEvent(
    val version: Int = 1,
    val id: String = UUID.randomUUID().toString(),
    val kind: String,
    val atMillis: Long,
    val epochDay: Long,
    val medId: String = "",
    val doseMg: Double = 0.0,
    val site: String = "",
    val grams: Int = 0,
    val waterMl: Int = 0,
) {
    fun isValid(now: Long): Boolean = version == 1 &&
        runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false) &&
        atMillis in 1..(now + 300_000) && epochDay in 1..(now / 86_400_000 + 2) &&
        kotlin.math.abs(epochDay - atMillis / 86_400_000) <= 1 &&
        when (kind) {
            "dose" -> medId.isNotBlank() && doseMg.isFinite() && doseMg > 0 && doseMg <= 100 && site in WatchContract.sites
            "weight" -> grams in 20_000..500_000
            "water" -> waterMl in 1..5000
            else -> false
        }
}
