package com.example.hadashboard

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.esphomeproto.api.*
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.*
import kotlin.concurrent.thread
import kotlin.math.round

class EsphomeApiService : Service() {
    private var serverSocket: ServerSocket? = null
    private var isRunning = true
    private val activeClients = Collections.synchronizedList(mutableListOf<SocketOutputStreamPair>())

    // Internal Media Player for actual playback
    private var internalMediaPlayer: MediaPlayer? = null

    private val CHANNEL_ID = "HADashboardServiceChannel"
    private val NOTIFICATION_ID = 1

    // Entity Keys
    private val SCREEN_KEY = 101
    private val LIGHT_KEY = 200
    private val BATT_KEY = 201
    private val STORAGE_KEY = 202
    private val RAM_KEY = 203
    private val UPTIME_KEY = 204
    private val RELOAD_KEY = 205
    private val KIOSK_KEY = 206
    private val ZOOM_IN_KEY = 207
    private val ZOOM_OUT_KEY = 208
    private val MEDIA_PLAYER_KEY = 300

    data class SocketOutputStreamPair(val socket: Socket, val output: OutputStream)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "HADashboard Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintains connection to Home Assistant"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun getAppVersion(): String {
        return try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            pInfo.versionName ?: "1.0.0"
        } catch (e: PackageManager.NameNotFoundException) {
            "1.0.0"
        }
    }

    private fun getUniqueMac(): String {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "00000000"
        return androidId.chunked(2).take(6).joinToString(":").uppercase()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HADashboard Active")
            .setContentText("Maintaining ESPHome connection...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        // Critical: Start as foreground service to prevent sleep killing
        startForeground(NOTIFICATION_ID, notification)

        isRunning = true
        startServer()
        startUpdateTimer()
        return START_STICKY
    }

    private fun startServer() {
        thread {
            try {
                serverSocket?.close() // Ensure any old socket is dead
                serverSocket = ServerSocket(6053)
                while (isRunning) {
                    val client = serverSocket?.accept()
                    client?.let {
                        val out = it.getOutputStream()
                        activeClients.add(SocketOutputStreamPair(it, out))
                        handleClient(it, out)
                    }
                }
            } catch (e: Exception) {
                Log.e("ESPHomeAPI", "Server error: ${e.message}")
            }
        }
    }

    private fun handleClient(socket: Socket, output: OutputStream) {
        thread {
            try {
                val input = socket.getInputStream()
                val prefs = getSharedPreferences("HADashboardPrefs", Context.MODE_PRIVATE)
                val deviceName = prefs.getString("unique_device_id", "Tablet") ?: "Tablet"
                val mac = getUniqueMac()
                val version = getAppVersion()

                while (isRunning && !socket.isClosed) {
                    val reserved = input.read()
                    if (reserved == -1) break
                    val length = readVarint(input)
                    val type = readVarint(input)
                    val payload = ByteArray(length)
                    input.read(payload)

                    when (type) {
                        1 -> { // HelloRequest
                            val resp = HelloResponse.newBuilder()
                                .setApiVersionMajor(1).setApiVersionMinor(10)
                                .setServerInfo("HADashboard v$version")
                                .setName(deviceName).build()
                            sendFrame(output, 2, resp.toByteArray())
                        }
                        3 -> sendFrame(output, 4, ConnectResponse.newBuilder().setInvalidPassword(false).build().toByteArray())
                        7 -> sendFrame(output, 8, PingResponse.newBuilder().build().toByteArray())
                        9 -> { // DeviceInfoResponse
                            val resp = DeviceInfoResponse.newBuilder()
                                .setName(deviceName)
                                .setFriendlyName(deviceName)
                                .setManufacturer("Twan Jaarsveld")
                                .setModel("HADashboard")
                                .setMacAddress(mac)
                                .setEsphomeVersion(version)
                                .build()
                            sendFrame(output, 10, resp.toByteArray())
                        }
                        11 -> { // ListEntitiesRequest
                            sendFrame(output, 17, ListEntitiesSwitchResponse.newBuilder()
                                .setObjectId("tablet_screen").setKey(SCREEN_KEY).setName("Screen").build().toByteArray())

                            sendFrame(output, 17, ListEntitiesSwitchResponse.newBuilder()
                                .setObjectId("kiosk_mode").setKey(KIOSK_KEY).setName("Kiosk Mode")
                                .setIcon("mdi:lock").build().toByteArray())

                            sendFrame(output, 15, ListEntitiesLightResponse.newBuilder()
                                .setObjectId("tablet_backlight").setKey(LIGHT_KEY).setName("Backlight")
                                .addSupportedColorModes(ColorMode.COLOR_MODE_BRIGHTNESS).build().toByteArray())

                            sendFrame(output, 16, ListEntitiesSensorResponse.newBuilder()
                                .setObjectId("tablet_battery").setKey(BATT_KEY).setName("Battery")
                                .setUnitOfMeasurement("%").setDeviceClass("battery")
                                .setAccuracyDecimals(0).build().toByteArray())

                            sendFrame(output, 16, ListEntitiesSensorResponse.newBuilder()
                                .setObjectId("tablet_storage").setKey(STORAGE_KEY).setName("Storage Used")
                                .setUnitOfMeasurement("%").setIcon("mdi:database")
                                .setAccuracyDecimals(0).build().toByteArray())

                            sendFrame(output, 16, ListEntitiesSensorResponse.newBuilder()
                                .setObjectId("tablet_ram").setKey(RAM_KEY).setName("RAM Used")
                                .setUnitOfMeasurement("%").setIcon("mdi:memory")
                                .setAccuracyDecimals(0).build().toByteArray())

                            sendFrame(output, 16, ListEntitiesSensorResponse.newBuilder()
                                .setObjectId("tablet_uptime").setKey(UPTIME_KEY).setName("Uptime")
                                .setUnitOfMeasurement("min").setIcon("mdi:timer-outline")
                                .setAccuracyDecimals(0).build().toByteArray())

                            sendFrame(output, 61, ListEntitiesButtonResponse.newBuilder()
                                .setObjectId("tablet_reload").setKey(RELOAD_KEY).setName("Reload Dashboard")
                                .setIcon("mdi:refresh").build().toByteArray())

                            sendFrame(output, 61, ListEntitiesButtonResponse.newBuilder()
                                .setObjectId("tablet_zoom_in").setKey(ZOOM_IN_KEY).setName("Zoom In")
                                .setIcon("mdi:magnify-plus").build().toByteArray())

                            sendFrame(output, 61, ListEntitiesButtonResponse.newBuilder()
                                .setObjectId("tablet_zoom_out").setKey(ZOOM_OUT_KEY).setName("Zoom Out")
                                .setIcon("mdi:magnify-minus").build().toByteArray())

                            sendFrame(output, 63, ListEntitiesMediaPlayerResponse.newBuilder()
                                .setObjectId("tablet_media")
                                .setKey(MEDIA_PLAYER_KEY)
                                .setName("$deviceName Speaker")
                                .setSupportsPause(true)
                                .setFeatureFlags(1 + 2 + 4 + 8)
                                .build().toByteArray())

                            sendFrame(output, 19, ListEntitiesDoneResponse.newBuilder().build().toByteArray())
                        }
                        20 -> sendAllStates(output)
                        33 -> { // SwitchCommandRequest
                            val cmd = SwitchCommandRequest.parseFrom(payload)
                            if (cmd.key == KIOSK_KEY) {
                                val action = if (cmd.state) "LOCK_APP" else "UNLOCK_APP"
                                sendBroadcast(Intent("DASHBOARD_COMMAND").putExtra("action", action))
                            } else if (cmd.key == SCREEN_KEY) {
                                val action = if (cmd.state) "SCREEN_ON" else "SCREEN_OFF"
                                sendBroadcast(Intent("DASHBOARD_COMMAND").putExtra("action", action))
                            }
                            sendFrame(output, 26, SwitchStateResponse.newBuilder().setKey(cmd.key).setState(cmd.state).build().toByteArray())
                        }
                        32 -> { // LightCommandRequest
                            val cmd = LightCommandRequest.parseFrom(payload)
                            if (cmd.key == LIGHT_KEY && cmd.hasBrightness) {
                                sendBroadcast(Intent("DASHBOARD_COMMAND").apply {
                                    putExtra("action", "SET_BRIGHTNESS")
                                    putExtra("value", (cmd.brightness * 255).toInt())
                                })
                            }
                            sendFrame(output, 24, LightStateResponse.newBuilder()
                                .setKey(LIGHT_KEY)
                                .setState(if (cmd.hasState) cmd.state else true)
                                .setBrightness(if (cmd.hasBrightness) cmd.brightness else 0.5f)
                                .setColorMode(ColorMode.COLOR_MODE_BRIGHTNESS).build().toByteArray())
                        }
                        62 -> { // ButtonCommandRequest
                            val cmd = ButtonCommandRequest.parseFrom(payload)
                            when (cmd.key) {
                                RELOAD_KEY -> sendBroadcast(Intent("DASHBOARD_COMMAND").putExtra("action", "RELOAD_URL"))
                                ZOOM_IN_KEY -> sendBroadcast(Intent("DASHBOARD_COMMAND").putExtra("action", "ZOOM_IN"))
                                ZOOM_OUT_KEY -> sendBroadcast(Intent("DASHBOARD_COMMAND").putExtra("action", "ZOOM_OUT"))
                            }
                        }
                        65 -> { // MediaPlayerCommandRequest
                            val cmd = MediaPlayerCommandRequest.parseFrom(payload)
                            if (cmd.key == MEDIA_PLAYER_KEY) {
                                if (cmd.hasMediaUrl && cmd.mediaUrl.isNotEmpty()) {
                                    playMediaUrl(cmd.mediaUrl)
                                }
                                if (cmd.hasCommand) {
                                    when (cmd.command) {
                                        MediaPlayerCommand.MEDIA_PLAYER_COMMAND_PLAY -> internalMediaPlayer?.start()
                                        MediaPlayerCommand.MEDIA_PLAYER_COMMAND_PAUSE -> internalMediaPlayer?.pause()
                                        MediaPlayerCommand.MEDIA_PLAYER_COMMAND_STOP -> {
                                            internalMediaPlayer?.stop()
                                            internalMediaPlayer?.reset()
                                        }
                                        else -> {}
                                    }
                                }
                                if (cmd.hasVolume) {
                                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                                    val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                    am.setStreamVolume(AudioManager.STREAM_MUSIC, (maxVol * cmd.volume).toInt(), 0)
                                }
                                sendMediaState(output)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ESPHomeAPI", "Client error: ${e.message}")
            } finally {
                activeClients.removeAll { it.socket == socket }
            }
        }
    }

    private fun playMediaUrl(url: String) {
        try {
            internalMediaPlayer?.stop()
            internalMediaPlayer?.release()
            internalMediaPlayer = null

            internalMediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                setDataSource(applicationContext, Uri.parse(url))
                prepareAsync()
                setOnPreparedListener { it.start() }
                setOnCompletionListener { it.reset() }
                setOnErrorListener { mp, what, extra ->
                    Log.e("ESPHomeAPI", "MediaPlayer Error: $what, $extra")
                    mp.reset()
                    true
                }
            }
        } catch (e: Exception) {
            Log.e("ESPHomeAPI", "Playback error: ${e.message}")
        }
    }

    private fun sendMediaState(output: OutputStream) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()

        val state = if (internalMediaPlayer?.isPlaying == true) {
            MediaPlayerState.MEDIA_PLAYER_STATE_PLAYING
        } else {
            MediaPlayerState.MEDIA_PLAYER_STATE_IDLE
        }

        sendFrame(output, 64, MediaPlayerStateResponse.newBuilder()
            .setKey(MEDIA_PLAYER_KEY)
            .setState(state)
            .setVolume(vol)
            .build().toByteArray())
    }

    private fun sendAllStates(output: OutputStream) {
        try {
            sendFrame(output, 26, SwitchStateResponse.newBuilder().setKey(SCREEN_KEY).setState(true).build().toByteArray())

            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val isKioskActive = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
            } else false
            sendFrame(output, 26, SwitchStateResponse.newBuilder().setKey(KIOSK_KEY).setState(isKioskActive).build().toByteArray())

            val curBr = try { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS).toFloat() / 255f } catch (e: Exception) { 0.5f }
            sendFrame(output, 24, LightStateResponse.newBuilder().setKey(LIGHT_KEY).setState(true).setBrightness(curBr).setColorMode(ColorMode.COLOR_MODE_BRIGHTNESS).build().toByteArray())

            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            sendFrame(output, 25, SensorStateResponse.newBuilder().setKey(BATT_KEY).setState(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toFloat()).build().toByteArray())

            val stat = StatFs(Environment.getDataDirectory().path)
            val storagePct = ((stat.blockCountLong - stat.availableBlocksLong).toFloat() / stat.blockCountLong.toFloat()) * 100f
            sendFrame(output, 25, SensorStateResponse.newBuilder().setKey(STORAGE_KEY).setState(round(storagePct)).build().toByteArray())

            val mi = ActivityManager.MemoryInfo()
            (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
            val ramPct = ((mi.totalMem - mi.availMem).toFloat() / mi.totalMem.toFloat()) * 100f
            sendFrame(output, 25, SensorStateResponse.newBuilder().setKey(RAM_KEY).setState(round(ramPct)).build().toByteArray())

            val uptimeMinutes = (SystemClock.elapsedRealtime().toFloat() / 60000f)
            sendFrame(output, 25, SensorStateResponse.newBuilder().setKey(UPTIME_KEY).setState(round(uptimeMinutes)).build().toByteArray())

            sendMediaState(output)
        } catch (e: Exception) { }
    }

    private fun startUpdateTimer() {
        Timer().scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                synchronized(activeClients) {
                    val iterator = activeClients.iterator()
                    while (iterator.hasNext()) {
                        val client = iterator.next()
                        if (client.socket.isClosed) {
                            iterator.remove()
                        } else {
                            sendAllStates(client.output)
                        }
                    }
                }
            }
        }, 10000, 30000)
    }

    private fun readVarint(input: InputStream): Int {
        var value = 0; var shift = 0
        while (true) {
            val b = input.read(); if (b == -1) return 0
            value = value or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return value
    }

    private fun sendFrame(output: OutputStream, type: Int, payload: ByteArray) {
        synchronized(output) {
            try {
                output.write(0x00)
                writeVarint(output, payload.size)
                writeVarint(output, type)
                output.write(payload)
                output.flush()
            } catch (e: Exception) { }
        }
    }

    private fun writeVarint(output: OutputStream, value: Int) {
        var v = value
        while (v >= 0x80) {
            output.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        output.write(v)
    }

    override fun onDestroy() {
        isRunning = false
        internalMediaPlayer?.release()
        serverSocket?.close()
        stopForeground(true) // Clean up the notification
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}