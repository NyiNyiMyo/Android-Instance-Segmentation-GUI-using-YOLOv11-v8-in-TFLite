package com.surendramaran.yolov11instancesegmentation

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.surendramaran.yolov11instancesegmentation.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.*
import android.view.View
import java.io.File
import java.io.FileOutputStream
import android.widget.TextView
import android.app.ActionBar
import android.graphics.Color
import android.graphics.Typeface

class MainActivity : AppCompatActivity(), InstanceSegmentation.InstanceSegmentationListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var instanceSegmentation: InstanceSegmentation
    private lateinit var drawImages: DrawImages
    private lateinit var previewView: PreviewView

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var cameraExecutor: ExecutorService? = null
    private var isCameraRunning = false

    private var currentFrameBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)


        supportActionBar?.apply {
            displayOptions = androidx.appcompat.app.ActionBar.DISPLAY_SHOW_CUSTOM
            val textView = TextView(this@MainActivity).apply {
                text = getString(R.string.app_name) // Your app name
                textSize = 16f // Change to smaller size
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            }
            customView = textView
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        previewView = binding.previewView
        drawImages = DrawImages(applicationContext)
        cameraExecutor = Executors.newSingleThreadExecutor()

        instanceSegmentation = InstanceSegmentation(
            context = applicationContext,
            modelPath = "yolo11n-seg_float16.tflite",
            labelPath = null,
            instanceSegmentationListener = this,
            message = {
                Toast.makeText(applicationContext, it, Toast.LENGTH_SHORT).show()
            }
        )

        // 🔘 Button click listeners
        binding.btnStart.setOnClickListener {
            if (!isCameraRunning) {
                checkPermissionAndStartCamera()
            }
        }

        binding.btnStop.setOnClickListener {
            stopCamera()
        }

        binding.btnSelectVideo.setOnClickListener {
            if (!isVideoRunning) {
                // Open gallery picker
                val intent = Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                pickVideoLauncher.launch(intent)
            } else {
                stopVideoProcessing()
            }
        }

        binding.btnStopVideo.setOnClickListener {
            stopVideoProcessing()
        }

    }

    // ------------------------------------------------------------------------

    private fun checkPermissionAndStartCamera() {
        val isGranted = REQUIRED_PERMISSIONS.all {
            ActivityCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
        }
        if (isGranted) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { map ->
            if (map.all { it.value }) {
                startCamera()
            } else {
                Toast.makeText(baseContext, "Camera permission required", Toast.LENGTH_LONG).show()
            }
        }

    private var videoJob: Job? = null
    private var retriever: MediaMetadataRetriever? = null
    private var isVideoRunning = false

    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            val code = result.resultCode
            if (code == RESULT_OK && data != null) {
                val uri = data.data
                if (uri != null) {
                    startVideoProcessing(uri)
                } else {
                    Toast.makeText(this, "No video selected", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Video selection cancelled", Toast.LENGTH_SHORT).show()
            }
        }

    // ------------------------------------------------------------------------

private fun startCamera() {
    if (isCameraRunning) return
    isCameraRunning = true

    binding.btnStart.visibility = android.view.View.GONE
    binding.btnSelectVideo.visibility = View.GONE
    binding.btnStop.visibility = android.view.View.VISIBLE

    val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

    cameraProviderFuture.addListener({
        try {
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }

            imageAnalyzer = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor!!, ImageAnalyzer())
                }

            // Bind safely
            cameraProvider?.unbindAll()
            cameraProvider?.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer
            )

            Log.i("CameraX", "Camera started successfully.")

        } catch (exc: SecurityException) {
            Log.e("CameraX", "Camera permission error: ${exc.message}")
            Toast.makeText(this, "Camera permission denied.", Toast.LENGTH_LONG).show()
            handleCameraStartFailure()

        } catch (exc: java.util.concurrent.ExecutionException) {
            Log.e("CameraX", "Camera provider execution failed: ${exc.message}")
            Toast.makeText(this, "Camera initialization failed.", Toast.LENGTH_LONG).show()
            handleCameraStartFailure()

        } catch (exc: IllegalStateException) {
            Log.e("CameraX", "Illegal camera state: ${exc.message}")
            Toast.makeText(this, "Camera is busy or not available.", Toast.LENGTH_LONG).show()
            handleCameraStartFailure()

        } catch (exc: Exception) {
            Log.e("CameraX", "Unexpected error: ${exc.message}", exc)
            Toast.makeText(this, "Unable to start camera.", Toast.LENGTH_LONG).show()
            handleCameraStartFailure()
        }

    }, ContextCompat.getMainExecutor(this))
}

private fun handleCameraStartFailure() {
    isCameraRunning = false
    binding.btnStart.visibility = android.view.View.VISIBLE
    binding.btnSelectVideo.visibility = View.VISIBLE
    binding.btnStop.visibility = android.view.View.GONE
    cameraProvider?.unbindAll()
}

    private fun stopCamera() {
        if (!isCameraRunning) return
        isCameraRunning = false

        cameraProvider?.unbindAll()
        imageAnalyzer?.clearAnalyzer()
        binding.ivTop.setImageResource(0)

        binding.btnStart.visibility = android.view.View.VISIBLE
        binding.btnSelectVideo.visibility = View.VISIBLE
        binding.btnStop.visibility = android.view.View.GONE
    }

    private fun copyVideoToCache(uri: Uri): File? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val tempFile = File(cacheDir, "temp_video.mp4")
            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            tempFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun startVideoProcessing(uri: Uri) {
        if (isVideoRunning) return
        isVideoRunning = true

        binding.btnSelectVideo.visibility = View.GONE
        binding.btnStart.visibility = android.view.View.GONE
        binding.btnStopVideo.visibility = View.VISIBLE

        retriever?.release()
        retriever = MediaMetadataRetriever()

        val localFile = copyVideoToCache(uri)
        if (localFile == null) {
            Toast.makeText(this, "Failed to read video", Toast.LENGTH_SHORT).show()
            handleVideoStartFailure()
            return
        }

        try {
            retriever?.setDataSource(localFile.path)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Cannot process video", Toast.LENGTH_SHORT).show()
            handleVideoStartFailure()
            return
        }

        val durationMs =
            retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: 0L
        if (durationMs <= 0L) {
            Toast.makeText(this, "Cannot read video duration", Toast.LENGTH_LONG).show()
            handleVideoStartFailure()
            return
        }
        val durationUs = durationMs * 1000L

        val fps =
            retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
                ?: 30f
        val frameIntervalUs = (1_000_000f / fps).toLong()

        videoJob = CoroutineScope(Dispatchers.Default).launch {
            var timeUs = 0L

            while (isActive && timeUs < durationUs && isVideoRunning) {
                val safeTimeUs = timeUs.coerceAtMost(durationUs - 1000)
                val frameBitmap: Bitmap? = try {
                    retriever?.getFrameAtTime(safeTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }

                frameBitmap?.let { bitmap ->
                    // Make a copy for processing
                    val inputBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)

                    // Save for drawing
                    currentFrameBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)

                    withContext(Dispatchers.Main) {
                        instanceSegmentation.invoke(inputBitmap)
                    }

                    bitmap.recycle()
                }

                timeUs += frameIntervalUs
                delay((1000f / fps).toLong())
            }

            withContext(Dispatchers.Main) {
                stopVideoProcessing()
                Toast.makeText(this@MainActivity, "Video finished", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopVideoProcessing() {
        if (!isVideoRunning) return
        isVideoRunning = false

        videoJob?.cancel()
        videoJob = null

        try {
            retriever?.release()
        } catch (e: Exception) { }
        retriever = null

        binding.ivTop.setImageResource(0)

        binding.btnSelectVideo.visibility = View.VISIBLE
        binding.btnStart.visibility = android.view.View.VISIBLE
        binding.btnStopVideo.visibility = View.GONE
    }

    private fun handleVideoStartFailure() {
        isVideoRunning = false
        videoJob?.cancel()
        videoJob = null

        try {
            retriever?.release()
        } catch (e: Exception) { }
        retriever = null

        binding.ivTop.setImageResource(0)
        binding.btnSelectVideo.visibility = View.VISIBLE
        binding.btnStart.visibility = android.view.View.VISIBLE
        binding.btnStopVideo.visibility = View.GONE
    }

    // ------------------------------------------------------------------------

    inner class ImageAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(imageProxy: ImageProxy) {
            // 1️⃣ Convert ImageProxy to ARGB_8888 Bitmap safely
            val bitmapBuffer = Bitmap.createBitmap(
                imageProxy.width,
                imageProxy.height,
                Bitmap.Config.ARGB_8888
            )

            try {
                val plane = imageProxy.planes[0]
                plane.buffer.rewind()
                bitmapBuffer.copyPixelsFromBuffer(plane.buffer)
            } catch (e: Exception) {
                Log.e("CameraX", "Failed to copy pixels: ${e.message}")
                imageProxy.close()
                return
            }

            // 2️⃣ Rotate bitmap according to rotationDegrees
            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer,
                0,
                0,
                bitmapBuffer.width,
                bitmapBuffer.height,
                matrix,
                true
            )
            bitmapBuffer.recycle()
            imageProxy.close()

            // 3️⃣ Save for DrawImages overlay
            currentFrameBitmap = rotatedBitmap.copy(Bitmap.Config.ARGB_8888, true)

            // 4️⃣ Run instance segmentation
            instanceSegmentation.invoke(rotatedBitmap)
            rotatedBitmap.recycle()
        }
    }


    // ------------------------------------------------------------------------

    override fun onError(error: String) {
        runOnUiThread {
            Toast.makeText(applicationContext, error, Toast.LENGTH_SHORT).show()
            binding.ivTop.setImageResource(0)
        }
    }

//    override fun onDetect(
//        interfaceTime: Long,
//        results: List<SegmentationResult>,
//        preProcessTime: Long,
//        postProcessTime: Long
//    ) {
//        val image = drawImages.invoke(results)
//        runOnUiThread {
//            binding.tvPreprocess.text = preProcessTime.toString()
//            binding.tvInference.text = interfaceTime.toString()
//            binding.tvPostprocess.text = postProcessTime.toString()
//            binding.ivTop.setImageBitmap(image)
//        }
//    }

    override fun onDetect(
        interfaceTime: Long,
        results: List<SegmentationResult>,
        preProcessTime: Long,
        postProcessTime: Long
    ) {
        runOnUiThread {
            currentFrameBitmap?.let { background ->
                val image = drawImages.invoke(results, background)
                binding.tvPreprocess.text = preProcessTime.toString()
                binding.tvInference.text = interfaceTime.toString()
                binding.tvPostprocess.text = postProcessTime.toString()
                binding.ivTop.setImageBitmap(image)
            }
        }
    }

    override fun onEmpty() {
        runOnUiThread { binding.ivTop.setImageResource(0) }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCamera()
        instanceSegmentation.close()
        cameraExecutor?.shutdown()
    }

    companion object {
        val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
