package com.zero.camml

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.IOException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import android.content.DialogInterface
import java.util.Date
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioManager
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var textureView: TextureView
    private lateinit var cameraCaptureSession: CameraCaptureSession
    private lateinit var cameraManager: CameraManager
    private lateinit var cameraDevice: CameraDevice
    private lateinit var captureRequestBuilder: CaptureRequest.Builder
    private lateinit var handler: Handler
    private lateinit var handlerThread: HandlerThread
    private lateinit var imageReader: ImageReader
    private lateinit var resultView: TextView
    private lateinit var textToSpeech: TextToSpeech
    private lateinit var modeButton: ImageButton
    private lateinit var modelButton: ImageButton
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var sharedPreferences: SharedPreferences
    private val conversationHistoryKey = "conversationhistory"
    private var apiEndpoint = "" // Store the API endpoint (IP and port)
    private val apiEndpointKey = "generate_caption"
    private val client = OkHttpClient()
    private var promptText = ""
    private var isSpeaking = false
    private var lastPrompt = ""
    private var modeValue = "Navigation"
    private var modelValue = "Gemini"
    private lateinit var audioManager: AudioManager
    private var isListeningForCommand = false
    private var currentVolume: Int = -1
    private var timer: Timer? = null
    private val mainHandler = Handler(Looper.getMainLooper()) // Handler for the main thread
    private var currentPrompt: String // Store the current prompt
    private val navigationPrompt = """
        Prompt:
            You are an indoor navigation assistant for smart glasses, providing guidance to the user. The user is moving within a building. Your task is to guide the user towards the nearest exit (door) while considering obstacles in their path.
            
            Instructions:
            
            *   **Exit Identification:** Identify doors within the camera's view. If multiple doors are visible, prioritize the one that appears to be the most direct route to an exit (based on its position and any visible signage).
            *   **Obstacle Detection:** Detect any obstacles in the user's path (e.g., furniture, boxes, people).
            *   **Path Guidance:** Provide clear and concise instructions to navigate towards the identified exit. Use relative directions (e.g., "Turn left," "Go straight," "Slightly to your right").
            *   **Detour Planning:** If an obstacle blocks the direct path to the exit, suggest a detour *within the camera's field of view*. Do not suggest detours that would require the user to turn away from the current view. Explain the detour clearly (e.g., "Walk to the right of the table," "Go around the chair on your left").
            *   **No Detour Available:** If no detour is possible within the current view (e.g., the entire path is blocked), inform the user: "Path blocked. Please adjust your position to find an alternate route."
            *   **Destination Reached:** When the user is close to the exit (door), state: "You have reached the exit."
            *   Only provide the answer. Do not provide an reply. Only provide directions if you are aware of the left right correctly.
            Question:
    """.trimIndent()

    data class Message(val text: String, val isUser: Boolean, val timestamp: String)

    private val detectionPrompt = """
        Prompt:
            You are an AI assistant for smart glasses, providing real-time visual information to the user. The user has asked, "What is in front of me?" Your task is to describe the scene, focusing on identifying and describing objects, their relationships to each other, and the overall context of the environment.
            
            Instructions:
            *   Prioritize identifying objects that could pose a safety hazard to the user (e.g., obstacles, spills, moving objects).
            *   Provide concise and informative descriptions of the most relevant objects in the scene.
            *   Describe the spatial relationships between objects (e.g., "A red chair is to your left," "A table is directly ahead").
            *   Avoid unnecessary details or repeating information. Focus on new information with each capture.
            *   If an object is very close to the camera (within arm's reach), append "OBJ" to its description. This acts as a warning.
            *   If asked a question, provide a specific answer.
            *   Only provide the answer. Do not provide an reply. 
            
            Question:
    """.trimIndent()

    private val ocrPrompt = """
        Prompt:
            You are an Optical Character Recognition (OCR) system embedded in smart glasses. Your task is to extract and read text from the image provided.
    
            Instructions:
            *   Accurately transcribe any text visible in the image.
            *   Focus on clear and legible text. Ignore background elements or noise.
            *   If the text appears to be part of a sign, label, or document, attempt to provide context (e.g., "Street sign: Main Street," "Product label: Apple Juice").
            *   If multiple blocks of text are present, read them in a logical order (e.g., top to bottom, left to right).
            *   If the text is unreadable or distorted, indicate "Text unreadable."
            *   Only provide the answer. Do not provide an reply. Do not generate information if the text ends without completion.
            
            Question:
    """.trimIndent()

    init {
        currentPrompt = navigationPrompt // Initialize with the default prompt
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Set language for TTS
            val result = textToSpeech.setLanguage(Locale.US)

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "Language not supported", Toast.LENGTH_SHORT).show()
            }

            textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    // Called when the TTS starts speaking
                }

                override fun onDone(utteranceId: String?) {
                    // Called when the TTS finishes speaking
                    Log.d("CameraActivity", "TTS has finished speaking")
                    isSpeaking = false
                    mainfn()
                }

                @Deprecated("Deprecated in Java", ReplaceWith("TODO(\"Not yet implemented\")"))
                override fun onError(p0: String?) {
                    TODO("Not yet implemented")
                }
            })
        } else {
            Toast.makeText(this, "Initialization failed", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.developer_options, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here.
        return when (item.itemId) {
            R.id.Gemini_API_Key -> {
                showApiKeyInputDialog()
                true
            }
            R.id.API_Endpoint -> {
                showApiKeyInputDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showApiEndpointInputDialog() {
        val editText = EditText(this)
        editText.hint = "Enter API Endpoint (IP:Port)"

        val layout = LinearLayout(this)
        layout.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        layout.orientation = LinearLayout.VERTICAL
        layout.gravity = Gravity.CENTER

        val marginInDp = 20
        val marginInPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            marginInDp.toFloat(),
            resources.displayMetrics
        ).toInt()

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(marginInPx, 0, marginInPx, 0)
        editText.layoutParams = params

        layout.addView(editText)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Developer Settings")
            .setMessage("Please enter your API Endpoint (IP:Port):")
            .setView(layout)
            .setPositiveButton("OK") { _, _ ->
                val endpoint = editText.text.toString().trim()
                if (endpoint.isNotEmpty()) {
                    handleApiEndpoint(endpoint)
                } else {
                    Toast.makeText(this, "API Endpoint cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
    }

    private fun handleApiEndpoint(endpoint: String) {
        apiEndpoint = endpoint // Save to the variable
        val editor = sharedPreferences.edit()
        editor.putString(apiEndpointKey, endpoint)
        editor.apply()

        Toast.makeText(this, "API Endpoint saved successfully", Toast.LENGTH_SHORT).show()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.layout_secondary)

        textureView = findViewById(R.id.textureview)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        sharedPreferences = getSharedPreferences("AppPreferences", MODE_PRIVATE)

        handlerThread = HandlerThread("CameraHandlerThread")
        handlerThread.start()
        handler = Handler(handlerThread.looper)
        apiEndpoint = sharedPreferences.getString(apiEndpointKey, "") ?: ""

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        sharedPreferences = getSharedPreferences("AppPreferences", MODE_PRIVATE)

        // Initial volume check
        currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        Log.d("VolumeChange", "Initial Volume: $currentVolume")

        startVolumeCheck()

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                openCamera()
            }

            override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                // Handle texture size changes if needed
            }

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
                // Handle texture updates if needed
                val textureView: TextureView = findViewById(R.id.textureview)

                val matrix = Matrix()
                matrix.setScale(1f, 1f) // Adjust scale factor (1f means no scaling)

                textureView.setTransform(matrix)

                }
        }

        checkPermissions()

        val sharedPreferences = getSharedPreferences("AppPreferences", MODE_PRIVATE)
        val apiKey = sharedPreferences.getString("GeminiApiKey", "")

        if (apiKey == "") {

            Toast.makeText(this, "Please create API Key", Toast.LENGTH_LONG).show()

        }

        println(apiKey)

        val generativeModel = GenerativeModel(
            modelName = "gemini-1.5-flash",
            apiKey = apiKey ?: "AIzaSyDM5TNMV437_9MNB75yVfiL1DHcqEfoVhE" // Use an empty string if API key is not found
        )

        promptText = ""

        resultView = findViewById(R.id.text_view)

        imageReader = ImageReader.newInstance(1080, 1920, ImageFormat.JPEG, 1)
        imageReader.setOnImageAvailableListener({ p0 ->
            val image = p0?.acquireLatestImage()
            val buffer = image!!.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val file = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "img.jpeg")
            val opStream = FileOutputStream(file)
            opStream.write(bytes)

            opStream.close()
            image.close()
            Toast.makeText(this@MainActivity, "Processing", Toast.LENGTH_SHORT).show()
            sendDataAPI(generativeModel, resultView)
        }, handler)

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)



        findViewById<Button>(R.id.voice_command).apply{
            setOnClickListener{
                startListeningForCommand()
            }
        }

        findViewById<Button>(R.id.voice_command).apply{

            setOnLongClickListener{
                val intent = Intent(this@MainActivity, ConversationHistoryActivity::class.java)
                startActivity(intent)
                true // Consume the long click
            }
        }

        findViewById<ImageButton>(R.id.model_button).apply{
            setOnClickListener{

                if (modelValue == "Gemini") {
                    modelValue = "API"
                } else if (modelValue == "API") {
                    modelValue = "Gemini"
                }

                Toast.makeText(this@MainActivity, modelValue, Toast.LENGTH_SHORT).show()

            }
        }

        findViewById<ImageButton>(R.id.model_button).apply{
            setOnLongClickListener{

                startListening()
                isSpeaking = true
                toggleSpeech()
                true

            }
        }


        findViewById<ImageButton>(R.id.mode_button).apply {
            setOnClickListener {
                when (modeValue) {
                    "Navigation" -> {
                        modeValue = "Detection"
                        currentPrompt = detectionPrompt // Correctly update the class-level variable
                    }
                    "Detection" -> {
                        modeValue = "OCR"
                        currentPrompt = ocrPrompt // Correctly update the class-level variable
                    }
                    "OCR" -> {
                        modeValue = "Navigation"
                        currentPrompt = navigationPrompt // Correctly update the class-level variable
                    }
                }

                Toast.makeText(this@MainActivity, modeValue, Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<ImageButton>(R.id.mode_button).apply {
            setOnLongClickListener {

                toggleSpeech()
                true
            }
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        modeButton = findViewById(R.id.mode_button)
        modeButton.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val diameter = view.width.coerceAtMost(view.height)
                outline.setOval(0, 0, diameter, diameter)
            }
        }
        modeButton.clipToOutline = true

        modelButton = findViewById(R.id.model_button)
        modelButton.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val diameter = view.width.coerceAtMost(view.height)
                outline.setOval(0, 0, diameter, diameter)
            }
        }
        modelButton.clipToOutline = true

        textToSpeech = TextToSpeech(this, this)

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Toast.makeText(this@MainActivity, "Listening", Toast.LENGTH_SHORT).show()
            }

            override fun onBeginningOfSpeech() {
                // Speech input started
            }

            override fun onRmsChanged(rmsdB: Float) {
                // The sound level in the audio stream has changed
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // More sound has been received
            }

            override fun onEndOfSpeech() {
            }

            override fun onError(error: Int) {
                Toast.makeText(this@MainActivity, getString(R.string.speech_error), Toast.LENGTH_SHORT).show()
                promptText = ""
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val string = matches[0]

                    if (isListeningForCommand) {
                        val command = matches[0].lowercase(Locale.getDefault()) // Convert to lowercase for easier matching
                        processVoiceCommand(command)
                        isListeningForCommand = false
                    } else {
                        promptText = string
                        isSpeaking = false
                        mainfn()
                    }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                // Partial recognition results
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                // Events related to the speech recognition
            }
        })

    }

    private fun addMessageToHistory(message: String, isUser: Boolean) {
        val editor = sharedPreferences.edit()
        val gson = Gson()

        val type = object : TypeToken<ArrayList<Message>>() {}.type
        val conversationHistory: ArrayList<Message> = gson.fromJson(sharedPreferences.getString(conversationHistoryKey, null), type) ?: ArrayList()

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val currentDateAndTime: String = sdf.format(Date())

        if (message.isNotEmpty()) {
            conversationHistory.add(Message(message, isUser, currentDateAndTime))
            val json = gson.toJson(conversationHistory)
            editor.putString(conversationHistoryKey, json)
            editor.apply()
        }
    }

    private fun startListeningForCommand() {
        mainHandler.post {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.RECORD_AUDIO), 2) // Request code 2 for command listening
                return@post
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())

            speechRecognizer.startListening(intent)
            isListeningForCommand = true
        }
    }

    private fun showApiKeyInputDialog() {
        // Create an EditText view to input the API key
        val editText = EditText(this)
        editText.hint = "Enter Gemini API Key"

        // Create a LinearLayout to hold the EditText and set margins
        val layout = LinearLayout(this)
        layout.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        layout.orientation = LinearLayout.VERTICAL
        layout.gravity = Gravity.CENTER

        // Convert DP to PX for margins
        val marginInDp = 20
        val marginInPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            marginInDp.toFloat(),
            resources.displayMetrics
        ).toInt()

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(marginInPx, 0, marginInPx, 0)
        editText.layoutParams = params

        layout.addView(editText)

        // Create an AlertDialog
        val dialog = AlertDialog.Builder(this)
            .setTitle("Developer Settings")
            .setMessage("Please enter your Gemini API key:")
            .setView(layout)
            .setPositiveButton("OK") { _, _ ->
                val apiKey = editText.text.toString().trim()
                if (apiKey.isNotEmpty()) {
                    // Save the API key or handle it as needed
                    handleApiKey(apiKey)
                } else {
                    Toast.makeText(this, "API key cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
    }

    private fun handleApiKey(apiKey: String) {
        // Save the API key to shared preferences or use it directly
        // Example: Saving to shared preferences
        val sharedPreferences = getSharedPreferences("AppPreferences", MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putString("GeminiApiKey", apiKey)
        editor.apply()

        // Inform the user
        Toast.makeText(this, "API key saved successfully", Toast.LENGTH_SHORT).show()

        val intent = Intent(this@MainActivity, MainActivity::class.java)
        startActivity(intent)
    }

    private fun mainfn() {

        val capReq = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        capReq.addTarget(imageReader.surface)
        cameraCaptureSession.capture(capReq.build(), null, null)

    }

    private fun startListening() {
        mainHandler.post { // Execute on the main thread
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
                return@post // Important: Return from the lambda, not the outer function
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())

            speechRecognizer.startListening(intent)
        }
    }

    private fun checkVolumeChange() {
        val newVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (newVolume != currentVolume) {

            if (newVolume > currentVolume) {
                mainHandler.post { // Use mainHandler here too

                    if (isSpeaking) {
                        toggleSpeech()
                    } else {
                        startListening()
                        isSpeaking = true
                        toggleSpeech()
                    }
                }
            } else {
                mainHandler.post { // Use mainHandler here too
                    startListeningForCommand()
                }
            }

            currentVolume = newVolume
        }
    }

    private fun toggleSpeech() {
        val text = resultView.text.toString()

        if (isSpeaking) {
            // Stop speaking
            textToSpeech.stop()
            isSpeaking = false
        } else {
            if (text.isNotEmpty()) {
                val params = Bundle()
                textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, params, "UniqueID")
                isSpeaking = true
            } else {
                Toast.makeText(this, "Text is empty", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopVolumeCheck(){
        timer?.cancel()
        timer = null
    }

    override fun onPause() {
        super.onPause()
        stopVolumeCheck()
    }

    override fun onResume() {
        super.onResume()
        startVolumeCheck()
    }

    private fun startVolumeCheck() {
        timer = Timer()
        timer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                handler.post {
                    checkVolumeChange()
                }
            }
        }, 0, 1000) // Check every 1 second
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun sendDataAPI(generativeModel: GenerativeModel, resultView: TextView) {

        if (modelValue == "Gemini") {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val imagePath =
                        getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.absolutePath + "/img.jpeg"
                    val imageFile = File(imagePath)

                    if (imageFile.exists()) {
                        val bitmap = BitmapFactory.decodeStream(FileInputStream(imageFile))

                        if (promptText == lastPrompt) promptText = ""
                        val inputContent = content {

                            image(bitmap)
                            text(currentPrompt + promptText)

                        }
                        lastPrompt = promptText

                        // Send the image to the API with the prompt
                        val response = generativeModel.generateContent(inputContent)

                        withContext(Dispatchers.Main) {
                            resultView.text = response.text.toString().replace("OBJ", "").trim()
                            addMessageToHistory(promptText, true) // Add user message to history
                            addMessageToHistory(
                                response.text.toString().replace("OBJ", "").trim(),
                                false
                            ) // Add AI response to history
                        }


                        checkAndVibrate(response.text.toString())

                        isSpeaking = false
                        toggleSpeech()

                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@MainActivity,
                                "Image file not found",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            }
        } else if (modelValue == "API") {
            // New HTTP POST logic
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val imagePath = getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.absolutePath + "/img.jpeg"
                    val imageFile = File(imagePath)

                    if (imageFile.exists()) {
                        val requestBody = MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart(
                                "image",
                                imageFile.name,
                                imageFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
                            )
                            .build()

                        val request = Request.Builder()
                            .url(apiEndpoint)
                            .post(requestBody)
                            .build()

                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@MainActivity, "API Error: ${response.code}", Toast.LENGTH_SHORT).show()
                                }
                                return@launch
                            }

                            val responseBody = response.body?.string()
                            withContext(Dispatchers.Main) {
                                resultView.text = responseBody?.trim() ?: "No response from API"
                                addMessageToHistory(promptText, true) // Add user message to history
                                addMessageToHistory(responseBody?.trim() ?: "No response from API", false) // Add API response to history
                            }
                            checkAndVibrate(responseBody ?: "")
                            isSpeaking = false
                            toggleSpeech()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Image file not found", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: IOException) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Network Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun checkAndVibrate(response: String) {
        if ("OBJ" in response) {
            // Get the Vibrator service
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

            // Check if the device has a vibrator
            if (vibrator.hasVibrator()) {
                // Vibrate for 500 milliseconds
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(500, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    vibrator.vibrate(500)
                }
            }
        }
    }


    @SuppressLint("MissingPermission")
    private fun openCamera() {
        try {
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(cameraDevice: CameraDevice) {
                    this@MainActivity.cameraDevice = cameraDevice


                    captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    val surface = Surface(textureView.surfaceTexture)
                    captureRequestBuilder.addTarget(surface)

                    cameraDevice.createCaptureSession(listOf(surface, imageReader.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            cameraCaptureSession = session
                            captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, handler)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Toast.makeText(this@MainActivity, "Camera configuration failed", Toast.LENGTH_SHORT).show()
                        }
                    }, handler)
                }

                override fun onDisconnected(cameraDevice: CameraDevice) {
                    cameraDevice.close()
                }

                override fun onError(cameraDevice: CameraDevice, error: Int) {
                    cameraDevice.close()
                    Toast.makeText(this@MainActivity, "Camera error: $error", Toast.LENGTH_SHORT).show()
                }
            }, handler)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to open camera: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            CAMERA_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    openCamera()
                } else {
                    Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
                }
            }

            2 -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    openCamera()
                } else {
                    Toast.makeText(this, "Audio permission required", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun processVoiceCommand(command: String) {
        when {
            command.contains("navigation") -> {
                modeValue = "Navigation"
                currentPrompt = navigationPrompt
                Toast.makeText(this, "Switching to Navigation mode", Toast.LENGTH_SHORT).show()
            }
            command.contains("detection") -> {
                modeValue = "Detection"
                currentPrompt = detectionPrompt
                Toast.makeText(this, "Switching to Detection mode", Toast.LENGTH_SHORT).show()
            }
            command.contains("ocr") -> {
                modeValue = "OCR"
                currentPrompt = ocrPrompt
                Toast.makeText(this, "Switching to OCR mode", Toast.LENGTH_SHORT).show()
            }
            command.contains("gemini") -> {
                modelValue = "Gemini"
                Toast.makeText(this, "Switching to Gemini model", Toast.LENGTH_SHORT).show()
            }
            command.contains("api") -> {
                modelValue = "API"
                Toast.makeText(this, "Switching to API model", Toast.LENGTH_SHORT).show()
            }
            else -> {
                Toast.makeText(this, "Command not recognized: $command", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
        if (::cameraDevice.isInitialized) {
            cameraDevice.close()
        }
        handler.removeCallbacksAndMessages(null)
        handlerThread.quitSafely()
    }

    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 1001
    }
}

// Create a new Activity: ConversationHistoryActivity.kt
class ConversationHistoryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ConversationAdapter
    private lateinit var sharedPreferences: SharedPreferences
    private val conversationHistoryKey = "conversationhistory"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_conversation_history) // Create this layout

        sharedPreferences = getSharedPreferences("AppPreferences", MODE_PRIVATE)
        recyclerView = findViewById(R.id.conversation_recycler_view) // Add this RecyclerView to the layout
        recyclerView.layoutManager = LinearLayoutManager(this)

        loadConversationHistory()

        val deleteButton : Button = findViewById(R.id.delete_history_button) // Find the delete button
        deleteButton.setOnClickListener {
            deleteConversationHistory()
        }
    }

    private fun loadConversationHistory() {
        val gson = Gson()
        val type = object : TypeToken<ArrayList<MainActivity.Message>>() {}.type
        val conversationHistory: ArrayList<MainActivity.Message> = gson.fromJson(sharedPreferences.getString(conversationHistoryKey, null), type) ?: ArrayList()

        adapter = ConversationAdapter(conversationHistory)
        recyclerView.adapter = adapter
    }

    private fun deleteConversationHistory() {
        val editor = sharedPreferences.edit()
        editor.remove(conversationHistoryKey) // Remove the history from SharedPreferences
        editor.apply()

        // Update the RecyclerView
        adapter.updateData(emptyList()) // Update the adapter with an empty list
        Toast.makeText(this,"Conversation History Cleared",Toast.LENGTH_SHORT).show()
    }
}

// Create an Adapter: ConversationAdapter.kt
class ConversationAdapter(private var messages: List<MainActivity.Message>) : RecyclerView.Adapter<ConversationAdapter.MessageViewHolder>() {

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val messageTextView: TextView = itemView.findViewById(R.id.message_text) // Create this TextView in item_message.xml
        val timestampTextView: TextView = itemView.findViewById(R.id.message_timestamp)
        val authorTextView: TextView = itemView.findViewById(R.id.message_author)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false) // Create item_message.xml
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        holder.messageTextView.text = message.text.toTitleCase()
        holder.timestampTextView.text = message.timestamp
        holder.authorTextView.text = if (message.isUser) "User" else "Model"
        // You can add logic here to differentiate user/AI messages visually
    }

    override fun getItemCount() = messages.size

    fun updateData(newMessages: List<MainActivity.Message>) {
        messages = newMessages
        notifyDataSetChanged()
    }
}

fun String.toTitleCase(): String {
    return split(" ").joinToString(" ") { it ->
        it.replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(
            Locale.ROOT
        ) else it.toString()
    } }
}