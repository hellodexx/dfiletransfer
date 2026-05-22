package com.example.dfiletransfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.MemoryFile
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.PatternSyntaxException
import kotlin.concurrent.thread

class FileTransferService : Service() {
    private var serverSocket: ServerSocket? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val serverPort = 9413
    private val channelId = "file_transfer_channel"

//    override fun onBind(intent: Intent): IBinder {
//        TODO("Return the communication channel to the service.")
//    }

    override fun onBind(p0: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
//        return super.onStartCommand(intent, flags, startId)
        Log.d("FileTransferService", "Service started command received.")

        // Create Notification Channel and start foreground profile immediately
        createNotificationChannel()
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }

        // Lock Wi-Fi network performance
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "FileTransferWifiLock").apply {
            acquire()
        }

        // Boot network listening thread loop
        startServer()

        return START_NOT_STICKY
    }

    private fun startServer() {
        thread {
            try {
                serverSocket = ServerSocket(serverPort)
                Log.d("FileTransferService", "Server listening safely on port $serverPort")

                while (serverSocket != null && !serverSocket!!.isClosed) {
                    val clientSocket = serverSocket!!.accept()
                    Log.d("FileTransferService", "Client linked up from: ${clientSocket.inetAddress.hostAddress}")
                    thread {
                        handleClient(clientSocket)
                    }
                }
            } catch (e: SocketException) {
                Log.d("FileTransferService", "Server socket terminated cleanly via stop call.")
            } catch (e: Exception) {
                Log.e("FileTransferService", "Server encountered error: ${e.message}")
            }
        }
    }

    private fun handleClient(clientSocket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(clientSocket.inputStream))
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                Log.d("FileTransferService", "Raw string received: $line")

                val packet = parseRequestPacket(line)

                if (packet != null) {
                    val type = packet.packetType
                    val expression = packet.regex

                    Log.d("FileTransferService", "Processing Type: $type, Regex: '$expression'")

                    // The branch block to execute different code based on packetType
                    when (type) {
                        0 -> {
                            Log.d("FileTransferService", "Action: Download initiated. Filtering files matching: '$expression'")
                            handleDownload(clientSocket, expression)
                        }
                        1 -> {
                            Log.d("FileTransferService", "Action: Upload initiated. Preparing to receive files matching: '$expression'")
                            handleUpload(clientSocket, expression)
                        }
                        2 -> {
                            Log.d("FileTransferService", "Action: List requested. Scanning directory using filter: '$expression'")
                            handleList(clientSocket, expression)
                        }
                        // Placeholder blocks for potential future features (3 to 9)
                        in 3..9 -> {
                            Log.d("FileTransferService", "Action: Packet type $type received. No action implemented yet.")
                        }
                        else -> {
                            Log.e("FileTransferService", "Unknown action type: $type")
                        }
                    }
                } else {
                    Log.e("FileTransferService", "Failed to parse incoming payload. Invalid format.")
                }
            }
        } catch (e: Exception) {
            Log.e("FileTransferService", "Client read error: ${e.message}")
        } finally {
            try {
                clientSocket.close()
                Log.e("FileTransferServer", "Client connection closed cleanly.")
            } catch (e: Exception) {
                Log.e("FileTransferServer", "Error closing client socket: ${e.message}")
            }
        }
    }

    private fun handleDownload(clientSocket: Socket, regexStr: String): Int {
        Log.d("FileTransferService", "Starting gallery scan for download matching expression: '$regexStr'")

        try {
            // Scan the system database
            val scanResult = findMatchingGalleryFiles(regexStr)
            val files = scanResult.fileList
            val totalCount = scanResult.fileList.size

            Log.d("FileTransferService", "Scan complete. Found $totalCount matching files.")

            // Send the header response packet (0 = OK status)
            sendResponsePacket(clientSocket, 0, totalCount)

            // Pass the open socket into the processor along with the file list
            processFoundFiles(clientSocket, files)
        } catch (e: Exception) {
            Log.e("FileTransferService", "Download process failure: ${e.message}")
            sendResponsePacket(clientSocket, 1, null)
        }

        return 0
    }

    private fun findMatchingGalleryFiles(userRegex: String): GalleryScanResult {
        val matchingFiles = mutableListOf<String>()

        // Prepare standard Regex matching rules based on user input string
        val pattern = try {
            if (userRegex.isBlank()) {
                null // Fallback to match everything
            } else {
                // Converts shell style wildcards like '202601*.jpg' safely into a functional Regex match format
                val converted = userRegex.replace(".", "\\.").replace("*", ".*")
                Regex(converted, RegexOption.IGNORE_CASE)
            }
        } catch (e: PatternSyntaxException) {
            Log.e("FileTransferService", "Malformed regex configuration syntax provided: ${e.message}")
            return GalleryScanResult(emptyList())
        }

        // SQLite filtering profile target: Only fetch Images and Videos
        val collectionUri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME)

        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )

        // Run query inside the system's content resolver database indexer
        contentResolver.query(collectionUri, projection, selection, selectionArgs, null)?.use { cursor ->
            val dataColumnIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val nameColumnIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val absolutePath = cursor.getString(dataColumnIndex) ?: continue
                val fileName = cursor.getString(nameColumnIndex) ?: ""

                // Apply our quick regex filtering check on the file string signature
                if (pattern == null || pattern.containsMatchIn(fileName)) {
                    matchingFiles.add(absolutePath)
                }
            }
        }

        return GalleryScanResult(fileList = matchingFiles)
    }

    // The requested response packet sending function
    private fun sendResponsePacket(socket: Socket, responseCode: Int, fileCount: Int?) {
        try {
            // Initialize an auto-flushing network stream writer
            val writer = PrintWriter(socket.getOutputStream(), true)

            // Format the files value: number string if files exist, empty string if null or zero
            val countString = if (fileCount == null || fileCount == 0) "" else fileCount.toString()

            // Build the precise packet layout: "<response code>|<number of files found>"
            val packetPayload = "$responseCode|$countString"

            // Push raw payload string over the open network pipe to the client terminal
            writer.println(packetPayload)
            Log.d("FileTransferService", "Sent response packet to client: $packetPayload")
        } catch (e: Exception) {
            Log.e("FileTransferService", "Error sending network packet: ${e.message}")
        }
    }

    private fun processFoundFiles(socket: Socket, fileList: List<String>) {
        Log.d("FileTransferService", "Processing ${fileList.size} files...")

        try {
            // Initialize the input stream reader to catch incoming client messages
            val reader = BufferedReader(InputStreamReader(socket.inputStream))

            for ((index, filePath) in fileList.withIndex()) {
                //  Print the target file path to Logcat
                Log.d("FileTransferService", "[$index/${fileList.size}] Ready to send file path: $filePath")

                // Block execution and wait for the client to send a "0" string
                var acknowledged = false
                while (!acknowledged) {
                    Log.d("FileTransferService", "Waiting for client start signal '0'...")
                    val clientSignal = reader.readLine()

                    // If the client disconnects unexpectedly, break out of the loop
                    if (clientSignal == null) {
                        Log.e("FileTransferService", "Client disconnected during file synchronization loop.")
                        return
                    }

                    if (clientSignal.trim() == "0") {
                        Log.d("FileTransferService", "Received start signal '0' from client. Moving forward.")
                        acknowledged = true
                    } else {
                        Log.w("FileTransferService", "Received invalid signal from client: '$clientSignal'. Still waiting for '0'...")
                    }
                }

                // Call the metadata extraction function
                val details = getFileDetails(filePath)

                // Send the metadata formatted packet straight to the client
                sendFileDetailsPacket(socket, details)

                // Call the function to stream the raw file binary payload
                sendFileContent(socket, filePath)

                Log.d("FileTransferService", "Successfully processed and sent: ${details.name}")

                // The loop will now advance to the next item and block again until another "0" is sent
            }

            Log.d("FileTransferService", "Finished processing all files in the batch request.")
        } catch (e: Exception) {
            Log.e("FileTransferService", "Error during file synchronization steps: ${e.message}")
        }

        Log.d("FileTransferService", "Finished processing all files.")
    }

    private fun handleUpload(clientSocket: Socket, regex: String) {
        Log.d("FileTransferService", "Starting multi-file upload processing pipeline...")

        try {
            val reader = BufferedReader(InputStreamReader(clientSocket.inputStream))
            val writer = PrintWriter(clientSocket.getOutputStream(), true)

            // 1. Read the initial initialization header packet: "<total_files>"
            val totalFilesLine = reader.readLine()
            if (totalFilesLine.isNullOrBlank()) return

            val totalFiles = totalFilesLine.toInt()
            Log.d("FileTransferService", "Client intends to upload $totalFiles files.")

            // 2. Loop explicitly for the total number of files coming in
            for (index in 0 until totalFiles) {
                Log.d("FileTransferService", "[$index/$totalFiles] Awaiting file metadata...")

                // Read metadata packet layout line: "<name>|<size>|<epoch_seconds>"
                val metadataLine = reader.readLine() ?: break
                val parts = metadataLine.split("|", limit = 3)
                if (parts.size < 3) continue

                val fileName = parts[0]
                val fileSize = parts[1].toLong()
                val epochSeconds = parts[2].toLong() // Extracted raw epoch time

                Log.d("FileTransferService", "[$index/$totalFiles] Incoming: $fileName ($fileSize bytes)")

                // 3. Open a writable MediaStore stream with explicit creation/modification timestamps injected
                var fileOutputStream: OutputStream? = null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)

                        // Force the Android database to index the file with its original timeline history
                        put(MediaStore.MediaColumns.DATE_MODIFIED, epochSeconds)
                        put(MediaStore.Images.Media.DATE_TAKEN, epochSeconds * 1000) // Expects milliseconds
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) fileOutputStream = contentResolver.openOutputStream(uri)
                } else {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val targetFile = File(downloadsDir, fileName)
                    fileOutputStream = FileOutputStream(targetFile)
                    // Fallback for older legacy filesystems
                    targetFile.setLastModified(epochSeconds * 1000)
                }

                if (fileOutputStream == null) {
                    Log.e("FileTransferService", "Could not allocate storage space stream. Aborting file.")
                    continue
                }

                // 4. Send the start acknowledgment "0" back to Python for this file iteration
                writer.println("0")

                // 5. Read the exact byte content allocation block from the input socket stream
                val inputStream = clientSocket.getInputStream()
                val buffer = ByteArray(8192)
                var totalBytesReceived = 0L

                while (totalBytesReceived < fileSize) {
                    val remaining = fileSize - totalBytesReceived
                    val targetReadSize = minOf(buffer.size.toLong(), remaining).toInt()

                    val bytesRead = inputStream.read(buffer, 0, targetReadSize)
                    if (bytesRead == -1) {
                        Log.e("FileTransferService", "Stream severed during transmission layout execution.")
                        break
                    }
                    fileOutputStream.write(buffer, 0, bytesRead)
                    totalBytesReceived += bytesRead
                }

                fileOutputStream.flush()
                fileOutputStream.close()
                Log.d("FileTransferService", "[$index/$totalFiles] Successfully saved: $fileName")
            }

            Log.d("FileTransferService", "Multi-file batch upload execution completed successfully.")

        } catch (e: Exception) {
            Log.e("FileTransferService", "Multi-upload error boundary reached: ${e.message}")
        }
    }


    private fun handleList(clientSocket: Socket, regexStr: String): Int {
        return 0
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "File Transfer Server Status",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("File Transfer Server Active")
            .setContentText("Listening for local network connections on port $serverPort...")
            .setSmallIcon(android.R.drawable.stat_sys_download) // Standard system anchor icon
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        Log.d("FileTransferService", "Destroying active service instance...")
        try {
            serverSocket?.close()
            serverSocket = null
            if (wifiLock?.isHeld == true) wifiLock?.release()
            wifiLock = null
        } catch (e: Exception) {
            Log.e("FileTransferService", "Error during teardown cleanup: ${e.message}")
        }
        super.onDestroy()
    }

    fun parseRequestPacket(rawData: String?): RequestPacket? {
        if (rawData.isNullOrBlank()) return null

        try {
            // Splits the string into two halves using the pipe symbol separator safely
            val parts = rawData.split("|", limit = 2)

            if (parts.isNotEmpty()) {
                // Extracts and converts the packet type element to an Int
                val packetTypeStr = parts[0].trim()
                val packetType = packetTypeStr.toInt()

                // Validates that the parsed type falls within your requested 0-9 rule limit
                if (packetType in 0..9) {
                    // If there's content after the pipe, use it. Otherwise, assign an empty string.
                    val regex = if (parts.size > 1) parts[1] else ""

                    // Return the final variables wrapped cleanly inside our structural data class
                    return RequestPacket(packetType, regex)
                }
            }
        } catch (e: NumberFormatException) {
            Log.e("FileTransferService", "Packet type portion is not a valid integer: ${e.message}")
        } catch (e: Exception) {
            Log.e("FileTransferService", "Parsing mistake: ${e.message}")
        }

        return null // Return null if the payload structure breaks rules or bounds
    }

    // Function to extract metadata details from an absolute file path
    private fun getFileDetails(filePath: String): FileDetails {
        val file = File(filePath)

        // Extract the direct filename, fallback to "unknown" if empty
        val name = file.name.ifBlank {"unknown_file"}

        // Retrieve the file size in bytes
        val size = file.length()

//        // Fetch the creation/last modified timestamp and convert to a readable format
//        val lastModifiedTimestamp = file.lastModified()
//        val sdf = SimpleDateFormat("yyyy-MM-dd_HH:mm:ss", Locale.getDefault())
//        val dateStr = sdf.format(Date(lastModifiedTimestamp))

        // Fetch the raw timestamp in milliseconds
        val lastModifiedMs = file.lastModified()

        // Convert milliseconds to seconds for standard Unix Epoch time
        val epochSeconds = lastModifiedMs / 1000
        val dateStr = epochSeconds.toString()

        return FileDetails(name, size, dateStr)
    }

    // Function to format and send the file details packet to the client over the socket
    private fun sendFileDetailsPacket(socket: Socket, details: FileDetails) {
        try {
            val writer = PrintWriter(socket.getOutputStream(), true)
            // Construct the strict packet format: "<name>|<size>|date"
            val packetPayload = "${details.name}|${details.size}|${details.date}"

            writer.println(packetPayload)
            Log.d("FileTransferService", "Sent file details packet: $packetPayload")
        } catch (e: Exception) {
            Log.e("FileTransferService", "Error sending file details packet: ${e.message}")
        }
    }

//    private fun sendFileContent(socket: Socket, filePath: String) {
//        var fileInputStream: FileInputStream? = null
//        try {
//            val file = File(filePath)
//            if (!file.exists()) {
//                Log.e("FileTransferService", "File does not exist: $filePath")
//                return
//            }
//
//            // Open the file for reading
//            fileInputStream = FileInputStream(file)
//
//            // Wrap the socket's output stream in a BufferedOutputStream for network efficiency
//            val outputStream = BufferedOutputStream(socket.getOutputStream())
//
//            // Define an optimized chunk buffer size (8 KB)
//            val buffer = ByteArray(8192)
//            var bytesRead: Int
//
//            Log.d("FileTransferService", "Streaming bytes for: ${file.name} (${file.length()} bytes)")
//
//            // Read the file into chunks and write them directly to the network pipe
//            while (fileInputStream.read(buffer).also { bytesRead = it } != -1) {
//                outputStream.write(buffer, 0, bytesRead)
//            }
//
//            // Explicitly flush the buffer to ensure all bytes are pushed out immediately
//            outputStream.flush()
//            Log.d("FileTransferService", "Finished sending file content for: ${file.name}")
//        } catch (e: Exception) {
//            Log.e("FileTransferService", "Error sending file content: ${e.message}")
//        } finally {
//            // Safely close the file stream resource
//            try {
//                fileInputStream?.close()
//                Log.d("FileTransferService", "File stream closed cleanly.")
//            } catch (e: Exception) {
//                Log.e("FileTransferService", "Error closing file stream: ${e.message}")
//            }
//        }
//    }

    private fun sendFileContent(socket: Socket, filePath: String) {
        // 1. Switch from legacy FileInputStream to generic InputStream to support Uris safely
        var fileInputStream: InputStream? = null
        try {
            val file = File(filePath)
            if (!file.exists()) {
                Log.e("FileTransferService", "File does not exist: $filePath")
                return
            }

            // 2. Convert the raw file path back into a valid MediaStore database entry Uri handle
            val contentUri = Uri.fromFile(file)

            // 3. Apply the GPS unredaction filter bypass check safely
            val originalUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.setRequireOriginal(contentUri)
            } else {
                contentUri
            }

            // 4. Open the stream using ContentResolver instead of FileInputStream
            fileInputStream = contentResolver.openInputStream(originalUri)

            if (fileInputStream == null) {
                Log.e("FileTransferService", "Failed to open input stream for path: $filePath")
                return
            }

            val outputStream = BufferedOutputStream(socket.getOutputStream())
            val buffer = ByteArray(8192)
            var bytesRead: Int

            Log.d("FileTransferService", "Streaming bytes for: ${file.name} (${file.length()} bytes)")

            while (fileInputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }

            outputStream.flush()
            Log.d("FileTransferService", "Finished sending file content for: ${file.name}")
        } catch (e: Exception) {
            Log.e("FileTransferService", "Error sending file content: ${e.message}")
        } finally {
            try {
                fileInputStream?.close()
                Log.d("FileTransferService", "File stream closed cleanly.")
            } catch (e: Exception) {
                Log.e("FileTransferService", "Error closing file stream: ${e.message}")
            }
        }
    }
}

data class RequestPacket(val packetType: Int, val regex: String)
data class GalleryScanResult(val fileList: List<String>)
data class FileDetails(val name: String, val size: Long, val date: String)
