package vegabobo.dsusideloader.preparation

import android.net.Uri
import kotlinx.coroutines.Job
import vegabobo.dsusideloader.core.StorageManager
import vegabobo.dsusideloader.model.DSUConstants
import vegabobo.dsusideloader.model.DSUInstallationSource
import vegabobo.dsusideloader.model.Session
import vegabobo.dsusideloader.service.PrivilegedProvider
import vegabobo.dsusideloader.util.OperationMode

class Preparation(
    private val storageManager: StorageManager,
    private val session: Session,
    private val job: Job,
    private val onStepUpdate: (step: InstallationStep) -> Unit,
    private val onPreparationProgressUpdate: (progress: Float) -> Unit,
    private val onCanceled: () -> Unit,
    private val onPreparationFinished: (preparedDSU: DSUInstallationSource) -> Unit
) : () -> Unit {

    private val userSelectedImageSize = session.userSelection.userSelectedImageSize
    private val userSelectedFileUri = session.userSelection.selectedFileUri

    override fun invoke() {
        if (session.getOperationMode() != OperationMode.ADB &&
            session.preferences.useBuiltinInstaller && PrivilegedProvider.isRoot()
        ) {
            prepareRooted()
            return
        } else {
            prepareForDSU()
        }
    }

    private fun prepareRooted() {
        val source: DSUInstallationSource = when (getExtension(userSelectedFileUri)) {
            "img" -> {
                DSUInstallationSource.SingleSystemImage(
                    userSelectedFileUri, getFileSize(userSelectedFileUri)
                )
            }
            "xz", "gz", "gzip" -> {
                val result = extractFile(userSelectedFileUri)
                DSUInstallationSource.SingleSystemImage(result.first, result.second)
            }
            "zip" -> {
                DSUInstallationSource.DsuPackage(userSelectedFileUri)
            }
            else -> {
                throw Exception("Unsupported filetype")
            }
        }
        if (!job.isCancelled)
            onPreparationFinished(source)
        else
            onCanceled()
    }

    private fun prepareForDSU() {
        storageManager.cleanWorkspaceFolder(true)
        val fileExtension = getExtension(userSelectedFileUri)
        val preparedFilePair =
            when (fileExtension) {
                "xz" -> prepareXz(userSelectedFileUri)
                "img" -> prepareImage(userSelectedFileUri)
                "gz" -> prepareGz(userSelectedFileUri)
                "zip" -> prepareZip(userSelectedFileUri)
                else -> throw Exception("Unsupported filetype")
            }

        val source: DSUInstallationSource

        val preparedUri = preparedFilePair.first
        val preparedFileSize = preparedFilePair.second

        source = if (fileExtension == "zip")
            DSUInstallationSource.DsuPackage(preparedUri)
        else
            DSUInstallationSource.SingleSystemImage(preparedUri, preparedFileSize)

        onStepUpdate(InstallationStep.WAITING_USER_CONFIRMATION)

        if (!job.isCancelled)
            onPreparationFinished(source)
        else
            onCanceled()
    }

    private fun prepareZip(inputZipFile: Uri): Pair<Uri, Long> {
        val uri = getSafeUri(inputZipFile)
        return Pair(uri, -1)
    }

    private fun prepareXz(inputXzFile: Uri): Pair<Uri, Long> {
        val outputFile = getFileName(inputXzFile)
        onStepUpdate(InstallationStep.DECOMPRESSING_XZ)
        val imgFile = FileUnPacker(
            storageManager,
            inputXzFile,
            outputFile,
            job,
            onPreparationProgressUpdate
        ).unpack()
        return prepareImage(imgFile.first)
    }

    private fun prepareImage(inputImageFile: Uri): Pair<Uri, Long> {
        val outputFile = "${getFileName(inputImageFile)}.img.gz"
        onStepUpdate(InstallationStep.COMPRESSING_TO_GZ)
        val pair = FileUnPacker(
            storageManager,
            inputImageFile,
            outputFile,
            job,
            onPreparationProgressUpdate
        ).pack()
        return Pair(pair.first, getFileSize(inputImageFile))
    }

    private fun prepareGz(inputGzFile: Uri): Pair<Uri, Long> {
        val uri = getSafeUri(inputGzFile)
        if (userSelectedImageSize != DSUConstants.DEFAULT_IMAGE_SIZE)
            return Pair(uri, -1)
        val outputFile = getFileName(uri)
        onStepUpdate(InstallationStep.DECOMPRESSING_GZIP)
        val pair =
            FileUnPacker(storageManager, uri, outputFile, job, onPreparationProgressUpdate).unpack()
        return Pair(inputGzFile, pair.second)
    }

    private fun extractFile(uri: Uri): Pair<Uri, Long> {
        return extractFile(uri, "system")
    }

    private fun extractFile(uri: Uri, partitionName: String): Pair<Uri, Long> {
        onStepUpdate(InstallationStep.EXTRACTING_FILE)
        return FileUnPacker(
            storageManager,
            uri,
            "${partitionName}.img",
            job,
            onPreparationProgressUpdate
        ).unpack()
    }

    private fun getSafeUri(uri: Uri): Uri {
        onStepUpdate(InstallationStep.COPYING_FILE)
        return storageManager.getUriSafe(uri)
    }

    private fun getFileName(uri: Uri): String {
        return storageManager.getFilenameFromUri(uri)
            .substringBeforeLast(".")
    }

    private fun getExtension(uri: Uri): String {
        return storageManager.getFilenameFromUri(uri)
            .substringAfterLast(".", "")
    }

    private fun getFileSize(uri: Uri): Long {
        return storageManager.getFilesizeFromUri(uri)
    }
}
