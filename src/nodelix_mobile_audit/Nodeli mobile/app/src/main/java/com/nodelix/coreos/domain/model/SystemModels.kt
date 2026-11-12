package com.nodelix.coreos.domain.model

data class DataFreshness(
    val isFromCache: Boolean,
    val serverTimestampMillis: Long?,
    val lastSyncMillis: Long?
)

data class MetricSample(
    val timestampMillis: Long,
    val cpuPercent: Double?,
    val ramPercent: Double?
)

data class SystemOverview(
    val cpuUsagePercent: Double?,
    val cpuCores: Int?,
    val cpuModel: String?,
    val loadAverage1m: Double?,
    val ramTotalBytes: Long?,
    val ramUsedBytes: Long?,
    val ramUsagePercent: Double?,
    val storageReady: Boolean,
    val storageRoot: String?,
    val storageMode: String?,
    val storageTotalBytes: Long?,
    val storageFreeBytes: Long?,
    val storageMessage: String?,
    val serverUptimeSeconds: Long?,
    val serverVersion: String?,
    val nodeVersion: String?,
    val lanAddresses: List<String>,
    val activeWebSocketClients: Int?,
    val responseTimeMs: Long?,
    val unavailableMetrics: List<String>,
    val history: List<MetricSample>,
    val freshness: DataFreshness
) {
    val ramAvailableBytes: Long?
        get() = if (ramTotalBytes != null && ramUsedBytes != null) {
            (ramTotalBytes - ramUsedBytes).coerceAtLeast(0L)
        } else null

    val storageUsedBytes: Long?
        get() = if (storageTotalBytes != null && storageFreeBytes != null) {
            (storageTotalBytes - storageFreeBytes).coerceAtLeast(0L)
        } else null

    val storageUsagePercent: Double?
        get() = if (storageTotalBytes != null && storageTotalBytes > 0L && storageUsedBytes != null) {
            (storageUsedBytes!!.toDouble() / storageTotalBytes.toDouble() * 100.0).coerceIn(0.0, 100.0)
        } else null
}
