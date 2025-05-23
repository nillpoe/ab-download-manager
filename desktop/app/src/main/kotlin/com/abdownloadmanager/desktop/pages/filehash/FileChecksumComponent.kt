package com.abdownloadmanager.desktop.pages.filehash

import androidx.compose.runtime.Immutable
import com.abdownloadmanager.shared.utils.*
import com.abdownloadmanager.shared.utils.mvi.ContainsEffects
import com.abdownloadmanager.shared.utils.mvi.ContainsScreenState
import com.abdownloadmanager.shared.utils.mvi.SupportsScreenState
import com.abdownloadmanager.shared.utils.mvi.supportEffects
import com.arkivanov.decompose.ComponentContext
import ir.amirab.downloader.downloaditem.DownloadItem
import ir.amirab.downloader.downloaditem.DownloadStatus
import ir.amirab.util.ifThen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.UUID
import kotlin.properties.Delegates

data class FileChecksumComponentConfig(
    val id: String = UUID.randomUUID().toString(),
    val itemIds: List<Long>,
)

class FileChecksumComponent(
    ctx: ComponentContext,
    val id: String,
    val itemIds: List<Long>,
    private val closeComponent: () -> Unit,
) : BaseComponent(ctx),
    KoinComponent,
    ContainsScreenState<FileChecksumUiState> by SupportsScreenState(FileChecksumUiState.default()),
    ContainsEffects<FileChecksumUiEffects> by supportEffects() {
    val downloadSystem: DownloadSystem by inject()

    private var downloadItems: List<DownloadItem> by Delegates.notNull()

    private val isChecking = MutableStateFlow(false)
    private val selectedDefaultAlgorithm: MutableStateFlow<FileChecksumAlgorithm> =
        MutableStateFlow(FileChecksumAlgorithm.default())

    fun onAlgorithmChange(algorithm: FileChecksumAlgorithm) {
        this.selectedDefaultAlgorithm.update { algorithm }
    }

    fun isDefaultAlgorithmNeeded(): Boolean {
        return state.value.items.any {
            it.savedChecksum == null
        }
    }

    init {
        scope.launch {
            load(itemIds)
            setup()

            if (!isDefaultAlgorithmNeeded()) {
                // user don't need to manually set checksum algorithm
                // start checking immediately
                startCheck()
            }
        }
        isChecking.onEach { isChecking ->
            setState { fileChecksumUiState ->
                fileChecksumUiState.copy(isChecking = isChecking)
            }
        }.launchIn(scope)
        selectedDefaultAlgorithm.onEach { algorithm ->
            setState { fileChecksumUiState ->
                fileChecksumUiState.copy(
                    // reset checksum algorithm
                    items = fileChecksumUiState.items.map { itemWithChecksum ->
                        itemWithChecksum.copy(
                            algorithm = getChecksumAlgorithmForItem(itemWithChecksum.downloadItem)
                        )
                    },
                    defaultAlgorithm = algorithm
                )
            }
        }.launchIn(scope)
    }

    private fun setup() {
        setState {
            it.copy(
                items = downloadItems.map { downloadItem ->
                    val savedChecksum = FileChecksum.fromNullableString(downloadItem.fileChecksum)
                    DownloadItemWithChecksum(
                        downloadItem = downloadItem,
                        checksumStatus = ChecksumStatus.Waiting,
                        algorithm = savedChecksum?.algorithm ?: selectedDefaultAlgorithm.value.algorithm,
                        savedChecksum = savedChecksum?.value,
                        calculatedChecksum = null,
                    )
                },
            )
        }
    }

    private suspend fun load(items: List<Long>) {
        downloadItems = items.mapNotNull {
            downloadSystem.getDownloadItemById(it)
        }
    }

    fun updateChecksum(
        downloadId: Long,
        fileChecksum: FileChecksum?,
    ) {
        scope.launch {
            val newChecksumString = fileChecksum?.toString()
            downloadSystem.downloadManager.updateDownloadItem(
                id = downloadId,
                updater = {
                    it.fileChecksum = newChecksumString
                }
            )
            // update this class internal download items
            downloadItems = downloadItems.map {
                it.ifThen(it.id == downloadId) {
                    copy(fileChecksum = newChecksumString)
                }
            }
            updateItem(downloadId) {
                var modified: DownloadItemWithChecksum = it
                // update the download item (in this component only)
                modified = modified.copy(
                    downloadItem = it.downloadItem.copy(
                        fileChecksum = newChecksumString
                    ),
                    savedChecksum = fileChecksum?.value
                )
                // update hash compare
                if (fileChecksum != null) {
                    val algorithm = fileChecksum.algorithm
                    if (it.algorithm != fileChecksum.algorithm) {
                        // reset calculated hash if the previous algorithm is different from the new one!
                        modified = modified.copy(
                            algorithm = algorithm,
                            calculatedChecksum = null,
                            checksumStatus = ChecksumStatus.Waiting,
                        )
                    } else if (modified.calculatedChecksum != null) {
                        // user previously started the check, and he calculated the hash
                        // so we compare saved hash with calculated hash for him
                        modified = modified.copy(
                            algorithm = algorithm,
                            checksumStatus = compareHashes(
                                savedChecksum = fileChecksum,
                                calculatedChecksum = FileChecksum(algorithm, modified.calculatedChecksum)
                            )
                        )
                    }
                } else {
                    // we don't have saved checksum, so we don't know if its matches or not!
                    if (it.checksumStatus is ChecksumStatus.Finished) {
                        modified = modified.copy(
                            checksumStatus = ChecksumStatus.Finished.Done,
                        )
                    }
                }
                modified
            }
        }
    }

    private suspend fun startCheck() {
        // clean old statuses
        setup()

        isChecking.update { true }
        try {
            withContext(Dispatchers.IO) {
                // some dude may change checksum when we are busy here
                // so always use the latest download items object!
                for (index in downloadItems.indices) {
                    processItem(downloadItems[index])
                }
            }
        } finally {
            isChecking.update { false }
        }
    }

    private fun processItem(item: DownloadItem) {
        val file = downloadSystem.getDownloadFile(item)
        if (item.status != DownloadStatus.Completed) {
            scope.launch {
                updateItemStatus(item.id, ChecksumStatus.Error.DownloadNotFinished)
            }
            return
        }
        if (!file.isFile) {
            scope.launch {
                updateItemStatus(item.id, ChecksumStatus.Error.FileNotFound)
            }
            return
        }
        try {
            val algorithm = getChecksumAlgorithmForItem(item)
            val hash = HashUtil.fileHash(
                algorithm = algorithm,
                file = file,
                onNewPercent = { percent ->
                    scope.launch {
                        updateItemStatus(item.id, ChecksumStatus.Checking(percent))
                    }
                }
            )
            val newStatus = compareHashes(
                FileChecksum.fromNullableString(item.fileChecksum),
                FileChecksum(algorithm, hash),
            )
            scope.launch {
                updateItem(item.id) {
                    it.copy(
                        checksumStatus = newStatus,
                        calculatedChecksum = hash,
                    )
                }
            }
        } catch (e: Exception) {
            scope.launch {
                updateItemStatus(item.id, ChecksumStatus.Error.Exception(e))
            }
        }
    }

    private fun compareHashes(
        savedChecksum: FileChecksum?, calculatedChecksum: FileChecksum
    ): ChecksumStatus.Finished {
        return if (savedChecksum == null) {
            ChecksumStatus.Finished.Done
        } else {
            if (savedChecksum == calculatedChecksum) {
                ChecksumStatus.Finished.Matches
            } else {
                ChecksumStatus.Finished.NotMatches
            }
        }
    }

    private fun getChecksumAlgorithmForItem(downloadItem: DownloadItem): String {
        return downloadItem.fileChecksum?.let {
            FileChecksum.fromString(it).algorithm
        } ?: selectedDefaultAlgorithm.value.algorithm
    }

    private fun updateItem(id: Long, updater: (DownloadItemWithChecksum) -> DownloadItemWithChecksum) {
        setState {
            it.copy(
                items = it.items.map { itemWithChecksum ->
                    itemWithChecksum.ifThen(itemWithChecksum.downloadItem.id == id) {
                        updater(itemWithChecksum)
                    }
                }
            )
        }
    }

    private fun updateItemStatus(id: Long, status: ChecksumStatus) {
        updateItem(id) {
            it.copy(checksumStatus = status)
        }
    }

    fun onRequestClose() {
        closeComponent()
    }

    fun onRequestStartCheck() {
        scope.launch {
            startCheck()
        }
    }

    fun bringToFront() {
        sendEffect(FileChecksumUiEffects.BringToFront)
    }
}

@Immutable
sealed interface ChecksumStatus {
    sealed interface Finished : ChecksumStatus {
        data object Matches : Finished
        data object NotMatches : Finished

        // just finished there is no saved checksum to compare it
        data object Done : Finished
    }

    data class Checking(val percent: Int) : ChecksumStatus
    sealed interface Error : ChecksumStatus {
        data object FileNotFound : Error
        data object DownloadNotFinished : Error
        data class Exception(val t: Throwable) : Error
    }

    data object Waiting : ChecksumStatus
}

@Immutable
data class DownloadItemWithChecksum(
    val downloadItem: DownloadItem,
    val checksumStatus: ChecksumStatus,
    val algorithm: String,
    val savedChecksum: String?,
    val calculatedChecksum: String?,
) {
    val isProcessing = checksumStatus is ChecksumStatus.Checking
    val isError = checksumStatus is ChecksumStatus.Error
}

@Immutable
data class FileChecksumUiState(
    val items: List<DownloadItemWithChecksum>,
    val isChecking: Boolean,
    val defaultAlgorithm: FileChecksumAlgorithm,
) {

    companion object {
        fun default() = FileChecksumUiState(
            items = emptyList(),
            isChecking = false,
            defaultAlgorithm = FileChecksumAlgorithm.default(),
        )
    }
}

@Immutable
sealed interface FileChecksumUiEffects {
    data object BringToFront : FileChecksumUiEffects
}
