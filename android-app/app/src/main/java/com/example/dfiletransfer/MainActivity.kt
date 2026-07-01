package com.example.dfiletransfer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BuildDrawCacheParams
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.dfiletransfer.ui.theme.DFileTransferTheme
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread

// Global variable to keep track of our active server socket instance
private var serverSocket: ServerSocket? = null
private const val SERVER_PORT = 9413

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DFileTransferTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    StartScreen(innerPadding = innerPadding)
                }
            }
        }
    }
}

@Composable
fun StartScreen(innerPadding: PaddingValues) {
    val context = LocalContext.current
    var ipAddress by remember { mutableStateOf("Fetching IP...") }
    var isServerRunning by remember { mutableStateOf(false) }

    // Array of required permissions based on Android version
    val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.ACCESS_MEDIA_LOCATION
        )
    } else {
        arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.ACCESS_MEDIA_LOCATION
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissions ->
            val allGranted = permissions.values.all { it }
            if (allGranted) {
                ipAddress = getPrivateIpAddress() ?: "No Wi-Fi Connection"
            }
        }
    )

    // Trigger the permission request on launch if not already granted
    LaunchedEffect(Unit) {
        val hasAllPermissions = requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        if (!hasAllPermissions) {
            permissionLauncher.launch(requiredPermissions)
        } else {
            ipAddress = getPrivateIpAddress() ?: "No Wi-Fi Connection"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "IP Address: $ipAddress",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = {
            val serviceIntent = Intent(context, FileTransferService::class.java)
            if (isServerRunning) {
                context.stopService(serviceIntent)
                isServerRunning = false
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                isServerRunning = true
            }
        }) {
            Text(text = if (isServerRunning) "Stop" else "Start")
        }
    }
}

fun getPrivateIpAddress(): String? {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        // We'll store a backup IP just in case we don't find a matching Wi-Fi name
        var fallbackIp: String? = null

        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()

            // Skip disabled, virtual loopback, or simulation interfaces
            if (networkInterface.isLoopback || !networkInterface.isUp) continue

            val interfaceName = networkInterface.name.lowercase()
            val addresses = networkInterface.inetAddresses

            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()

                // We only care about standard IPv4 configurations
                if (!address.isLoopbackAddress && address is Inet4Address) {
                    val ipCandidate = address.hostAddress ?: continue

                    // 1. Highest Priority: Target interfaces explicitly named as Wi-Fi adapters
                    if (interfaceName.contains("wlan") || interfaceName.contains("p2p")) {
                        return ipCandidate
                    }

                    // 2. Secondary Priority: Target your explicit home subnet footprint
                    if (ipCandidate.startsWith("192.168.")) {
                        return ipCandidate
                    }

                    // Keep any other valid private IP as a fallback (like 10.x.x.x or 172.16.x.x)
                    fallbackIp = ipCandidate
                }
            }
        }
        return fallbackIp
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return null
}

@Preview
@Composable
fun MainScreenPreview() {
    DFileTransferTheme {
        StartScreen(innerPadding = PaddingValues())
    }
}