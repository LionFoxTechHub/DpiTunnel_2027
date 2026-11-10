package com.example.tunnel

import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe traffic meter that measures real bytes transferred through the TUN interface
 * and outbound socket connections.
 * Strictly calculates actual rates over time intervals without simulation.
 */
class TrafficMeter {

    private val _totalUploaded = AtomicLong(0L)
    private val _totalDownloaded = AtomicLong(0L)

    private val _uploadBytesSinceTick = AtomicLong(0L)
    private val _downloadBytesSinceTick = AtomicLong(0L)

    private val _currentUploadRateBps = AtomicLong(0L)
    private val _currentDownloadRateBps = AtomicLong(0L)

    val totalUploadedBytes: Long
        get() = _totalUploaded.get()

    val totalDownloadedBytes: Long
        get() = _totalDownloaded.get()

    val currentUploadRateBps: Long
        get() = _currentUploadRateBps.get()

    val currentDownloadRateBps: Long
        get() = _currentDownloadRateBps.get()

    fun recordUpload(bytes: Long) {
        if (bytes <= 0) return
        _totalUploaded.addAndGet(bytes)
        _uploadBytesSinceTick.addAndGet(bytes)
    }

    fun recordDownload(bytes: Long) {
        if (bytes <= 0) return
        _totalDownloaded.addAndGet(bytes)
        _downloadBytesSinceTick.addAndGet(bytes)
    }

    /**
     * Called every second to calculate instantaneous upload/download speed in bytes/sec.
     */
    fun tick(): Pair<Long, Long> {
        val up = _uploadBytesSinceTick.getAndSet(0L)
        val down = _downloadBytesSinceTick.getAndSet(0L)

        _currentUploadRateBps.set(up)
        _currentDownloadRateBps.set(down)

        return Pair(up, down)
    }

    fun reset() {
        _totalUploaded.set(0L)
        _totalDownloaded.set(0L)
        _uploadBytesSinceTick.set(0L)
        _downloadBytesSinceTick.set(0L)
        _currentUploadRateBps.set(0L)
        _currentDownloadRateBps.set(0L)
    }
}
