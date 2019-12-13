package com.example.customcamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.CameraMetadata.LENS_FACING_BACK
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.support.v4.content.ContextCompat.checkSelfPermission
import android.support.v7.app.AppCompatActivity
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.AdapterView
import android.widget.AdapterView.OnItemSelectedListener
import android.widget.ArrayAdapter
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*


class MainActivity : AppCompatActivity()  {

    var mBackgroundThread: HandlerThread? = null
    var mBackgroundHandler: Handler? = null

    private var surfaceTextureListener: TextureView.SurfaceTextureListener? = null

    private var state = STATE_PREVIEW
    private var sensorOrientation = 0
    private lateinit var previewRequestBuilder: CaptureRequest.Builder
    private var flashSupported = false

    var mCameraManager: CameraManager? = null
    private var appContext: Context? = this
    private var cameraId = ""
    var formattedDate = ""
    var countRepeat = 0

    @SuppressLint("SimpleDateFormat")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
            || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        } else {
            mCameraManager = appContext?.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraId = getCameraWithFacing(mCameraManager!!, LENS_FACING_BACK)

            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture?, p1: Int, p2: Int) { }

                override fun onSurfaceTextureUpdated(p0: SurfaceTexture?) { }

                @SuppressLint("NewApi")
                override fun onSurfaceTextureDestroyed(p0: SurfaceTexture?): Boolean {
                    return p0!!.isReleased
                }

                override fun onSurfaceTextureAvailable(p0: SurfaceTexture?, p1: Int, p2: Int) {
                    openCamera()
                }
            }
            texture_view.surfaceTextureListener = surfaceTextureListener

            val date = Calendar.getInstance().time
            val formatter = SimpleDateFormat("yyyyMMdd_HHmmss")
            formattedDate = formatter.format(date)

            ArrayAdapter.createFromResource(this, R.array.counts_array, R.layout.custom_spinner_item).also {
                adapter ->
                    adapter.setDropDownViewResource(R.layout.custom_spinner_dropdown)
                    spinner.adapter = adapter
            }

            spinner.apply {
                setSelection(0)
                onItemSelectedListener = object : OnItemSelectedListener {
                    override fun onNothingSelected(p0: AdapterView<*>?) { }

                    override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, p3: Long) {
                        countRepeat = position + 1
                    }
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.isNotEmpty()) {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && grantResults[1] == PackageManager.PERMISSION_GRANTED
                ) {
                    Toast.makeText(this, "camera permission granted", Toast.LENGTH_SHORT).show()
                    mCameraManager =
                        appContext?.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    cameraId = getCameraWithFacing(mCameraManager!!, LENS_FACING_BACK)
                    try {
                        if (texture_view.isAvailable) {
                            openCamera()
                        } else {
                            texture_view.surfaceTextureListener = surfaceTextureListener
                        }
                    } catch (e: CameraAccessException) {
                        e.printStackTrace()
                    }
                } else {
                    Toast.makeText(
                        this,
                        "Some of the required permissions was denied, closing the application",
                        Toast.LENGTH_LONG
                    ).show()
                    Thread.sleep(1000)
                    finish()
                }
            } else {
                Toast.makeText(
                    this,
                    "Permissions was denied, closing the application",
                    Toast.LENGTH_LONG
                ).show()
                Thread.sleep(1000)
                finish()
            }
        }
    }

    private fun getCameraWithFacing(manager: CameraManager, lensFacing: Int): String {
        var possibleCandidate: String? = null
        val cameraIdList = manager.cameraIdList
        var myCameraId = ""
        if (cameraIdList.isEmpty()) {
            return ""
        }
        for (cameraId in cameraIdList) {
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: continue
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
            // Check if the flash is supported.
            flashSupported =
                characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing != null && facing == lensFacing) {
                myCameraId = cameraId
            }
            possibleCandidate = myCameraId
        }
        return possibleCandidate ?: cameraIdList[0]
    }

    override fun onStart() {
        super.onStart()

        get_picture.setOnClickListener {
            if (isOpen)
                lockFocus()
        }
    }

    private fun lockFocus() {
        try {
            // This is how to tell the camera to lock focus.
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_START)
            // Tell #captureCallback to wait for the lock.
            state = STATE_WAITING_LOCK
            mCaptureSession?.capture(previewRequestBuilder.build(), captureCallback,
                mBackgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    override fun onResume() {
        super.onResume()
        val date = Calendar.getInstance().time
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss")
        formattedDate = formatter.format(date)
        startBackgroundThread()
        if (texture_view.isAvailable) {
            openCamera()
        } else {
            texture_view.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        stopBackgroundThread()
        closeCamera()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (isOpen) {
            closeCamera()
        }
    }

    private fun startBackgroundThread() {
        mBackgroundThread = HandlerThread("CameraBackground")
        mBackgroundThread?.start()
        mBackgroundHandler = Handler(mBackgroundThread?.looper)
    }

    private fun stopBackgroundThread() {
        mBackgroundThread!!.quitSafely()
        try {
            mBackgroundThread!!.join()
            mBackgroundThread = null
            mBackgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

        private var mCameraDevice: CameraDevice? = null
        private var mCaptureSession: CameraCaptureSession? = null
        private lateinit var mCameraCallback: CameraDevice.StateCallback
        var mImageReader: ImageReader? = null
        var repeats = 0
//        private var file: File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "your best photo.jpg")

//        var files: ArrayList<File> = createFiles()

        var captureCallback = object : CameraCaptureSession.CaptureCallback() {
            private fun process(result: CaptureResult) {
                when (state) {
                    STATE_PREVIEW -> Unit // Do nothing when the camera preview is working normally.
                    STATE_WAITING_LOCK -> capturePicture(result)
                    STATE_WAITING_PRECAPTURE -> {
                        // CONTROL_AE_STATE can be null on some devices
                        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                        if (aeState == null ||
                            aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                            aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED
                        ) {
                            state = STATE_WAITING_NON_PRECAPTURE
                        }
                    }
                    STATE_WAITING_NON_PRECAPTURE -> {
                        // CONTROL_AE_STATE can be null on some devices
                        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                        if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                            state = STATE_PICTURE_TAKEN
                            captureStillPicture()
                        }
                    }
                }
            }

            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                process(result)
            }

            override fun onCaptureProgressed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                partialResult: CaptureResult
            ) {
                process(partialResult)
            }

            private fun capturePicture(result: CaptureResult) {
                val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                if (afState == null) {
                    captureStillPicture()
                } else if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                    || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                ) {
                    // CONTROL_AE_STATE can be null on some devices
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                        state = STATE_PICTURE_TAKEN
                        captureStillPicture()
                    } else {
                        runPrecaptureSequence()
                    }
                }
            }
        }

        private var file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "IMG_CustomCamera_${formattedDate}.jpg")

        private val mOnImageAvailableListener: ImageReader.OnImageAvailableListener =
            ImageReader.OnImageAvailableListener {
//                files = createFiles()
                mBackgroundHandler?.post(ImageSaver(it.acquireNextImage(), file))
                repeats++
            }


        private val isOpen: Boolean
            get() = mCameraDevice != null

//        private fun createFiles(): ArrayList<File> {
//            for (i in 1..countRepeat) {
//                files.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "IMG_CustomCamera_${formattedDate}.jpg"))
//            }
//            return files
//        }

        fun openCamera() {
            if (checkSelfPermission(
                    appContext!!,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                mCameraCallback = object : CameraDevice.StateCallback() {
                    override fun onOpened(cameraDevice: CameraDevice) {
                        mCameraDevice = cameraDevice
                        createCameraPreviewSession(this@MainActivity)
                    }

                    override fun onDisconnected(cameraDevice: CameraDevice) {
                        cameraDevice.close()
                        mCameraDevice = null
                    }

                    override fun onError(cameraDevice: CameraDevice, error: Int) { }
                }
                mCameraManager?.openCamera(cameraId, mCameraCallback, mBackgroundHandler)
            } else {
                Toast.makeText(appContext, "Требуются разрешения", Toast.LENGTH_SHORT).show()
                requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
                return
            }
        }

        private fun createCameraPreviewSession(activity: MainActivity) {
            mImageReader = ImageReader.newInstance(1920, 1800, ImageFormat.JPEG, 1)
            mImageReader!!.setOnImageAvailableListener(mOnImageAvailableListener, null)

            var textureView = activity.findViewById<TextureView>(R.id.texture_view)
            var texture: SurfaceTexture = textureView.surfaceTexture
            texture.setDefaultBufferSize(1920, 1080)
            var surface = Surface(texture)
            try {
                previewRequestBuilder = mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                previewRequestBuilder.addTarget(surface)
                mCameraDevice!!.createCaptureSession(
                    listOf(surface, mImageReader!!.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {}

                        override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                            mCaptureSession = cameraCaptureSession
                            try {
                                // Auto focus should be continuous for camera preview.
                                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                // Flash is automatically enabled when necessary.
                                setAutoFlash(previewRequestBuilder)
                                mCaptureSession?.setRepeatingRequest(previewRequestBuilder.build(), captureCallback, mBackgroundHandler)
                            } catch (e: CameraAccessException) {
                                e.printStackTrace()
                            }
                        }

                    },
                    mBackgroundHandler
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        private fun setAutoFlash(requestBuilder: CaptureRequest.Builder) {
            if (flashSupported) {
                requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
            }
        }

        fun closeCamera() {
            mCaptureSession?.close()
            mCaptureSession = null

            mImageReader?.close()
            mImageReader = null
            mCameraDevice?.close()
            mCameraDevice = null
        }

        private fun captureStillPicture() {
            try {
                if (this == null || mCameraDevice == null) return
                val rotation = this.windowManager.defaultDisplay.rotation

                // This is the CaptureRequest.Builder that we use to take a picture.
                val captureBuilder = mCameraDevice?.createCaptureRequest(
                    CameraDevice.TEMPLATE_STILL_CAPTURE)?.apply {
                    addTarget(mImageReader?.surface)

                    // Sensor orientation is 90 for most devices, or 270 for some devices (eg. Nexus 5X)
                    // We have to take that into account and rotate JPEG properly.
                    // For devices with orientation of 90, we return our mapping from ORIENTATIONS.
                    // For devices with orientation of 270, we need to rotate the JPEG 180 degrees.
                    set(CaptureRequest.JPEG_ORIENTATION,
                        (ORIENTATIONS.get(rotation) + sensorOrientation) % 360 + 90)

                    // Use the same AE and AF modes as the preview.
                    set(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }?.also { setAutoFlash(it) }

                val captureCallback = object : CameraCaptureSession.CaptureCallback() {

                    override fun onCaptureCompleted(session: CameraCaptureSession,
                                                    request: CaptureRequest,
                                                    result: TotalCaptureResult) {
                        Toast.makeText(appContext, "Image was saved", Toast.LENGTH_SHORT).show()
                        unlockFocus()
                    }
                }

                mCaptureSession?.apply {
                    stopRepeating()
                    abortCaptures()
                    capture(captureBuilder?.build(), captureCallback, null)
                }
            } catch (e: CameraAccessException) {
                e.printStackTrace()
            }
        }

        private fun unlockFocus() {
            try {
                // Reset the auto-focus trigger
                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                setAutoFlash(previewRequestBuilder)
                mCaptureSession?.capture(previewRequestBuilder.build(), captureCallback,
                    mBackgroundHandler)
                // After this, the camera will go back to the normal state of preview.
                state = STATE_PREVIEW
                mCaptureSession?.setRepeatingRequest(previewRequestBuilder.build(), captureCallback,
                    mBackgroundHandler)
            } catch (e: CameraAccessException) {
                e.printStackTrace()
            }
        }

        private fun runPrecaptureSequence() {
            try {
                // This is how to tell the camera to trigger.
                previewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                // Tell #captureCallback to wait for the precapture sequence to be set.
                state = STATE_WAITING_PRECAPTURE
                mCaptureSession?.capture(previewRequestBuilder.build(), captureCallback,
                    mBackgroundHandler)
            } catch (e: CameraAccessException) {
                e.printStackTrace()
            }

        }

    inner class ImageSaver constructor(image: Image, private val mFile: File) : Runnable {
        private var mImage: Image? = null

        init {
            mImage = image
        }

        override fun run() {
            val buffer = mImage!!.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            var output: FileOutputStream? = null
            try {
                output = FileOutputStream(mFile)
                output.write(bytes)
            } catch (e: IOException) {
                e.printStackTrace()
            } finally {
                mImage!!.close()
                if (output != null) {
                    try {
                        output.close()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }

                }
            }
        }
    }

    companion object {
        private val ORIENTATIONS = SparseIntArray()
        init {
            ORIENTATIONS.append(Surface.ROTATION_0, 90)
            ORIENTATIONS.append(Surface.ROTATION_90, 0)
            ORIENTATIONS.append(Surface.ROTATION_180, 270)
            ORIENTATIONS.append(Surface.ROTATION_270, 180)
        }
        private val STATE_PREVIEW = 0
        private val STATE_WAITING_LOCK = 1
        private val STATE_WAITING_PRECAPTURE = 2
        private val STATE_WAITING_NON_PRECAPTURE = 3
        private val STATE_PICTURE_TAKEN = 4
    }
}