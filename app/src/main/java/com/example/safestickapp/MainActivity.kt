package com.example.safestickapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import java.util.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech

    private lateinit var voiceArea: Button
    private lateinit var sosArea: Button
    private lateinit var tvStatus: TextView

    private lateinit var bluetoothAdapter: android.bluetooth.BluetoothAdapter
    private var bluetoothSocket: android.bluetooth.BluetoothSocket? = null
    private val myUUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var spokenNumber: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        voiceArea = findViewById(R.id.voiceArea)
        sosArea = findViewById(R.id.sosArea)

        tts = TextToSpeech(this, this)

        requestPermissions()

        // 🎤 VOICE BUTTON
        voiceArea.setOnClickListener {
            startCommandVoice()
        }

        // 🚨 SOS BUTTON
        sosArea.setOnClickListener {
            sendSOS()
        }

        sosArea.setOnLongClickListener {
            //clearEmergencyNumber()
            startNumberVoice()
            true
        }

        connectBluetooth()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            speak("SafeStick ready")
        }
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    // 🎤 GOOGLE VOICE INPUT
    private fun startVoiceInput() {
        speak("Say emergency number")

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now")

        try {
            startActivityForResult(intent, 1)
        } catch (_: Exception) {
            Toast.makeText(this, "Voice not supported", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCommandVoice() {
        speak("Say your command")

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)

        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

        startActivityForResult(intent, 2)
    }

    private fun startNumberVoice() {
        speak("Say emergency number digit by digit")

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)

        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

        startActivityForResult(intent, 1)
    }

    // 🎤 RESULT FROM VOICE
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode == RESULT_OK) {
            val result = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = result?.get(0)?.lowercase() ?: ""

            // 🔢 NUMBER MODE
            if (requestCode == 1) {

                val number = convertWordsToNumber(spokenText)

                if (number.length >= 8) {

                    val prefs = getSharedPreferences("SafeStickPrefs", MODE_PRIVATE)
                    prefs.edit().putString("emergencyNumber", number).apply()

                    tvStatus.text = "Saved: $number"

                    // 🔊 Speak back for confirmation
                    speak("You said $number. Contact saved")

                } else {
                    speak("I did not detect a valid number. Please try again slowly")
                }
            }

            // 🗺 COMMAND MODE
            if (requestCode == 2) {

                if (spokenText.contains("help")) {
                    sendSOS()
                }

                else if (spokenText.contains("navigate to")) {
                    val destination = spokenText.replace("navigate to", "").trim()
                    openGoogleMaps(destination)
                }

                else {
                    speak("Command not recognized")
                }
            }
        }
    }

    // 🚨 SEND SOS
    private fun sendSOS() {
        val prefs = getSharedPreferences("SafeStickPrefs", MODE_PRIVATE)
        val number = prefs.getString("emergencyNumber", null)

        if (number == null) {
            speak("No contact saved")
            return
        }

        val locationText = getLocationText()

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.SEND_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val sms = "Emergency! I need help! $locationText"

        getSystemService(SmsManager::class.java)
            .sendTextMessage(number, null, sms, null, null)

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.CALL_PHONE
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        startActivity(Intent(Intent.ACTION_CALL, "tel:$number".toUri()))

        speak("SOS sent")
        tvStatus.text = "SOS sent to $number"
    }

    // 📍 LOCATION
    private fun getLocationText(): String {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return "Location unavailable"
        }

        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val location: Location? =
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)

        return if (location != null)
            "https://maps.google.com/?q=${location.latitude},${location.longitude}"
        else "Location unavailable"
    }

    // 🔢 WORD → NUMBER
    private fun convertWordsToNumber(input: String): String {

        // Remove unwanted words
        val cleaned = input
            .replace("billion", "")
            .replace("million", "")
            .replace("thousand", "")
            .lowercase()

        // Extract digits if present
        val digitsOnly = cleaned.filter { it.isDigit() }
        if (digitsOnly.length >= 8) return digitsOnly

        val map = mapOf(
            "zero" to "0", "one" to "1", "two" to "2",
            "three" to "3", "four" to "4", "five" to "5",
            "six" to "6", "seven" to "7", "eight" to "8",
            "nine" to "9"
        )

        return cleaned.split(" ").joinToString("") { map[it] ?: "" }
    }

    private fun requestPermissions() {

        val permissions = mutableListOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1)
    }

    private fun clearEmergencyNumber() {
        val prefs = getSharedPreferences("SafeStickPrefs", MODE_PRIVATE)
        prefs.edit {
            remove("emergencyNumber")
        }
        speak("Contact cleared")
        tvStatus.text = "Contact cleared"
    }

    private fun openGoogleMaps(destination: String) {
        val uri = "google.navigation:q=$destination".toUri()

        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.setPackage("com.google.android.apps.maps")

        startActivity(intent)
        speak("Opening maps")
    }

    private fun listenFromArduino() {
        Thread {
            try {
                val input = bluetoothSocket?.inputStream
                val buffer = ByteArray(1024)

                while (true) {
                    val bytes = input?.read(buffer)
                    if (bytes != null && bytes > 0) {

                        val msg = String(buffer, 0, bytes).trim()

                        runOnUiThread {
                            // Update the distance on screen
                            tvStatus.text = msg

                            if (msg.startsWith("DIST:")) {
                                val dist = msg.replace("DIST:", "").toIntOrNull() ?: 0

                                // Only alert if obstacle is within threshold (1-50 cm)
                                if (dist in 1..80) {
                                    // 🔊 Speak
                                    speak("Obstacle detected $dist centimeters ahead")

                                    // 📳 Vibrate
                                    vibratePhone(400) // vibrate for 400ms
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    speak("Bluetooth disconnected")
                }
            }
        }.start()
    }

    // Helper function to vibrate the phone
    private fun vibratePhone(durationMs: Long = 500) {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(durationMs, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(durationMs)
        }
    }

    private fun connectBluetooth() {

        val bluetoothManager = getSystemService(android.bluetooth.BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager.adapter

        val device = bluetoothAdapter.bondedDevices.find { it.name == "HC-05" }

        if (device == null) {
            speak("HC 05 not paired")
            return
        }

        Thread {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        runOnUiThread {
                            speak("Bluetooth permission not granted")
                        }
                        return@Thread
                    }
                }

                bluetoothAdapter.cancelDiscovery()

                bluetoothSocket = device.createRfcommSocketToServiceRecord(myUUID)
                bluetoothSocket?.connect()

                runOnUiThread {
                    tvStatus.text = "Bluetooth Connected"
                    speak("Bluetooth connected")
                }

                listenFromArduino()

            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "Connection failed"
                    speak("Bluetooth connection failed")
                }
            }
        }.start()
    }



    override fun onDestroy() {
        super.onDestroy()
        tts.stop()
        tts.shutdown()
    }
}