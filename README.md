# dfiletransfer

A fast and optimized local network file transfer utility. It features an **Android Foreground Service** acting as a high-performance socket server and a **Python CLI Client** for multi-file synchronization.

---

## 📁 Repository Structure

```text
dfiletransfer/
├── android-app/    # Jetpack Compose Android app (Socket Server & MediaStore integration)
├── python-app/     # Python Command Line Interface (CLI Tool)
└── README.md       # Project documentation
```

---

## 🚀 Quick Start Guide

### 1. Run the Android Server
1. Open the `android-app` project in Android Studio.
2. Build and run it on your physical Android device.
3. Make sure your phone is connected to the same Wi-Fi network as your computer.
4. Grant the required notification and storage permissions when prompted.
5. Note the private IP address displayed on the screen, then tap **Start**.

### 2. Run the Python Client
Navigate to your `python-app` folder on your laptop and execute the script using the options below.

#### ⬇️ Download files from Phone to Laptop
To search and download matching gallery files using a wildcard/regex filter:
```bash
python dfiletransfer.py -c <PHONE_IP> -p 9413 -d "202605*.jpg"
```

#### ⬆️ Upload files from Laptop to Phone
To transfer local files back to the phone's public **Downloads** directory:
```bash
python dfiletransfer.py -c <PHONE_IP> -p 9413 -u "local_folder/*.jpg"
```

---

## 🛠️ Features

* **Multi-Threaded Architecture**: Handles large batches of multiple files sequentially without memory leaks.
* **Metadata & GPS Preservation**: Preserves original photo metadata, full camera EXIF profiles, and GPS location coordinates.
* **Timestamp Synchronization**: Synchronizes original creation and modification epoch timestamps across devices.
* **Lock-Proof Connections**: Runs inside an Android Foreground Service with high-performance Wi-Fi locks to stay alive even when the phone screen is fully locked.
* **Memory Efficient**: Streams large files chunk-by-chunk using optimized 8 KB buffers instead of loading entire files into RAM.

---

## 📝 License
This project is open-source and available under the [MIT License](LICENSE).
