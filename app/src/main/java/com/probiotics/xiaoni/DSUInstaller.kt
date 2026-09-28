package com.probiotics.xiaoni

import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.util.Log
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.lsposed.hiddenapibypass.HiddenApiBypass

class DSUInstaller(
    private val service: IPrivilegedService,
    private val userdataSize: Long,
    private val zipPath: String,
    private val onInstallationError: (errorInfo: String) -> Unit,
    private val onInstallationProgressUpdate: (progress: Float, partition: String) -> Unit,
    private val onInstallationSuccess: () -> Unit,
) {
    private val tag = "DSUInstaller"
    private var installationJob: Job = Job()

    companion object {
        const val DEFAULT_SLOT = "dsu"
        const val SHARED_MEM_SIZE: Int = 524288
        const val MIN_PROGRESS_TO_PUBLISH = (1 shl 27).toLong()
    }

    private class MappedMemoryBuffer(var mBuffer: ByteBuffer?) : AutoCloseable {
        override fun close() {
            if (mBuffer != null) {
                SharedMemory.unmap(mBuffer!!)
                mBuffer = null
            }
        }
    }

    private val UNSUPPORTED_PARTITIONS: List<String> = listOf(
        "vbmeta",
        "boot",
        "userdata",
        "dtbo",
        "super_empty",
        "system_other",
        "scratch",
    )

    private fun isPartitionSupported(partitionName: String): Boolean =
        !UNSUPPORTED_PARTITIONS.contains(partitionName)

    private fun getFdDup(sharedMemory: SharedMemory): ParcelFileDescriptor {
        return HiddenApiBypass.invoke(
            sharedMemory.javaClass,
            sharedMemory,
            "getFdDup",
        ) as ParcelFileDescriptor
    }

    private fun shouldInstallEntry(name: String): Boolean {
        if (!name.endsWith(".img")) {
            return false
        }
        val partitionName = name.substringBeforeLast(".").substringAfterLast("/")
        return isPartitionSupported(partitionName)
    }

    private fun publishProgress(bytesRead: Long, totalBytes: Long, partition: String) {
        var progress = 0F
        if (totalBytes != 0L && bytesRead != 0L) {
            progress = (bytesRead.toFloat() / totalBytes.toFloat())
        }
        onInstallationProgressUpdate(progress, partition)
    }

    private fun installWritablePartition(
        partition: String,
        partitionSize: Long,
        readOnly: Boolean = false,
    ) {
        val job = Job()
        CoroutineScope(Dispatchers.IO + job).launch {
            createNewPartition(partition, partitionSize, readOnly)
            job.complete()
        }
        publishProgress(0L, partitionSize, partition)
        var prevInstalledSize = 0L
        while (job.isActive) {
            val progress = service.installationProgress
            val installedSize = progress.bytes_processed
            if (installedSize > prevInstalledSize + MIN_PROGRESS_TO_PUBLISH) {
                prevInstalledSize = installedSize
                publishProgress(installedSize, partitionSize, partition)
            }
            runBlocking { delay(100) }
        }
        if (!service.closePartition()) {
            Log.e(tag, "Failed to install $partition partition")
            onInstallationError("Failed to close partition: $partition")
            return
        }

        // Ensure a 100% mark is published.
        if (prevInstalledSize != partitionSize) {
            publishProgress(partitionSize, partitionSize, partition)
        }
        Log.d(
            tag,
            "Partition $partition installed, readOnly: $readOnly, partitionSize: $partitionSize",
        )
    }

    private fun installImage(
        partition: String,
        uncompressedSize: Long,
        inputStream: InputStream,
        readOnly: Boolean = true,
    ) {
        val sis = SparseInputStream(
            BufferedInputStream(inputStream),
        )
        val partitionSize = if (sis.unsparseSize != -1L) sis.unsparseSize else uncompressedSize
        createNewPartition(partition, partitionSize, readOnly)
        SharedMemory.create("dsu_buffer_$partition", SHARED_MEM_SIZE)
            .use { sharedMemory ->
                MappedMemoryBuffer(sharedMemory.mapReadWrite()).use { mappedBuffer ->
                    val fdDup = getFdDup(sharedMemory)
                    service.setAshmem(fdDup, sharedMemory.size.toLong())
                    publishProgress(0L, partitionSize, partition)
                    var installedSize: Long = 0
                    val readBuffer = ByteArray(sharedMemory.size)
                    val buffer = mappedBuffer.mBuffer
                    var numBytesRead: Int
                    while (0 < sis.read(readBuffer, 0, readBuffer.size)
                            .also { numBytesRead = it }
                    ) {
                        if (installationJob.isCancelled) {
                            return
                        }
                        buffer!!.position(0)
                        buffer.put(readBuffer, 0, numBytesRead)
                        service.submitFromAshmem(numBytesRead.toLong())
                        installedSize += numBytesRead.toLong()
                        publishProgress(installedSize, partitionSize, partition)
                    }
                    publishProgress(partitionSize, partitionSize, partition)
                }
            }

        if (!service.closePartition()) {
            Log.d(tag, "Failed to install $partition partition")
            onInstallationError("Failed to close partition: $partition")
            return
        }
        Log.d(
            tag,
            "Partition $partition installed, readOnly: $readOnly, partitionSize: $partitionSize",
        )
    }

    private fun installStreamingZipUpdate(inputStream: InputStream): Boolean {
        val zis = ZipInputStream(inputStream)
        var entry: ZipEntry?
        while (zis.nextEntry.also { entry = it } != null) {
            val fileName = entry!!.name
            if (shouldInstallEntry(fileName)) {
                installImageFromAnEntry(entry!!, zis)
            } else {
                Log.d(tag, "$fileName installation is not supported, skip it.")
            }
            if (installationJob.isCancelled) {
                break
            }
        }
        return true
    }

    private fun installImageFromAnEntry(entry: ZipEntry, inputStream: InputStream) {
        val fileName = entry.name
        Log.d(tag, "Installing: $fileName")
        val partitionName = fileName.substringBeforeLast(".").substringAfterLast("/")
        val uncompressedSize = entry.size
        installImage(partitionName, uncompressedSize, inputStream)
    }

    fun startInstallation() {
        installationJob = Job() // 初始化 Job
        service.setDynProp()
        if (service.isInUse) {
            onInstallationError("Dynamic System is already in use")
            return
        }
        if (service.isInstalled) {
            onInstallationError("DSU is already installed, please discard it first")
            return
        }
        service.forceStopPackage("com.android.dynsystem")
        if (!service.startInstallation(DEFAULT_SLOT)) {
            onInstallationError("Failed to start installation")
            return
        }
        installWritablePartition("userdata", userdataSize)
        if (installationJob.isCancelled) {
            return
        }
        FileInputStream(zipPath).use { fis ->
            installStreamingZipUpdate(fis)
        }
        if (!installationJob.isCancelled) {
            service.finishInstallation()
            service.setEnable(true, false)
            Log.d(tag, "Installation finished successfully.")
            onInstallationSuccess()
        }
    }

    fun createNewPartition(partition: String, partitionSize: Long, readOnly: Boolean) {
        val result = service.createPartition(partition, partitionSize, readOnly)
        if (result != 0) {
            Log.d(
                tag,
                "Failed to create $partition partition, error code: $result",
            )
            installationJob.cancel()
            onInstallationError("Failed to create partition: $partition (error $result)")
        }
    }

    fun cancel() {
        installationJob.cancel()
    }
}
