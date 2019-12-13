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
import android.util.Log
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

    var myCameras = arrayListOf<CameraService>()
    var mCameraManager: CameraManager? = null
    private var appContext: Context? = this
    private var cameraId = ""
    private var camera1 = 0
    private var camera2 = 1
    var formattedDate = ""
    var countRepeat = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
            || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        } else {
            mCameraManager = appContext?.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraId = getCameraWithFacing(mCameraManager!!, LENS_FACING_BACK)
            for (camera in mCameraManager!!.cameraIdList) {
                myCameras.add(CameraService(mCameraManager!!, camera, this))
            }
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture?, p1: Int, p2: Int) { }

                override fun onSurfaceTextureUpdated(p0: SurfaceTexture?) {
                    if (myCameras[camera1] != null)
                        if (!myCameras[camera1].isOpen)
                            myCameras[camera1].openCamera()
                }

                @SuppressLint("NewApi")
                override fun onSurfaceTextureDestroyed(p0: SurfaceTexture?): Boolean {
                    return p0!!.isReleased
                }

                override fun onSurfaceTextureAvailable(p0: SurfaceTexture?, p1: Int, p2: Int) {
                        if (myCameras[camera1] != null)
                            if (!myCameras[camera1].isOpen)
                                myCameras[camera1].openCamera()
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
                        for (camera in mCameraManager!!.cameraIdList) {
                            myCameras.add(CameraService(mCameraManager!!, camera, this))
                        }
                        if (myCameras[camera1] != null)
                            if (!myCameras[camera1].isOpen)
                                myCameras[camera1].openCamera()
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
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing != null && facing == lensFacing) {
                myCameraId = cameraId
            }

            val sizesJPEG = map.getOutputSizes(ImageFormat.JPEG)

            sizesJPEG?.let {
                for (item in sizesJPEG) {
                    Log.d("Camera", "w:" + item.width + " h:" + item.height)
                }
            }

            possibleCandidate = myCameraId
        }
        return possibleCandidate ?: cameraIdList[0]
    }

    override fun onStart() {
        super.onStart()

        get_picture.setOnClickListener {
            if (myCameras[camera1].isOpen)
                myCameras[camera1].makePhoto()
        }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
    }

    override fun onPause() {
        stopBackgroundThread()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (myCameras[camera1].isOpen)
            myCameras[camera1].closeCamera()
    }

    private fun startBackgroundThread() {
        mBackgroundThread = HandlerThread("CameraBackground")
        mBackgroundThread!!.start()
        mBackgroundHandler = Handler(mBackgroundThread!!.looper)
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


    inner class CameraService(
        private val mCameraManager: CameraManager,
        private val mCameraID: String,
        private val activity: MainActivity
    ) {

        private var mCameraDevice: CameraDevice? = null
        private lateinit var mCaptureSession: CameraCaptureSession
        private lateinit var mCameraCallback: CameraDevice.StateCallback
        var mImageReader: ImageReader? = null
        var repeats = 0
//        private var file: File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "your best photo.jpg")

        var files: ArrayList<File> = createFiles()

        private val mOnImageAvailableListener: ImageReader.OnImageAvailableListener =
            ImageReader.OnImageAvailableListener { reader ->
                mBackgroundHandler!!.post(ImageSaver(reader.acquireNextImage(), files[repeats]))
                repeats++
            }


        val isOpen: Boolean
            get() = mCameraDevice != null

        private fun createFiles(): ArrayList<File> {
            for (i in 1..countRepeat) {
                files.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "IMG_CustomCamera_${formattedDate}.jpg"))
            }
            return files
        }

        fun openCamera() {
            try {
                if (checkSelfPermission(
                        activity.applicationContext,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    mCameraCallback = object : CameraDevice.StateCallback() {
                        override fun onOpened(cameraDevice: CameraDevice) {
                            mCameraDevice = cameraDevice
                            createCameraPreviewSession(activity)
                        }

                        override fun onDisconnected(p0: CameraDevice) {
                            mCameraDevice!!.close()
                            mCameraDevice = null
                        }

                        override fun onError(cameraDevice: CameraDevice, error: Int) { }
                    }
                    mCameraManager.openCamera(mCameraID, mCameraCallback, mBackgroundHandler)
                }
            } catch (e: CameraAccessException) {
            }
        }

        private fun createCameraPreviewSession(activity: MainActivity) {
            mImageReader = ImageReader.newInstance(2592, 1936, ImageFormat.JPEG, 1)
            mImageReader!!.setOnImageAvailableListener(mOnImageAvailableListener, null)

            var textureView = activity.findViewById<TextureView>(R.id.texture_view)
            var texture: SurfaceTexture = textureView.surfaceTexture
            texture.setDefaultBufferSize(2592, 1936)
            var surface = Surface(texture)
            try {
                val builder: CaptureRequest.Builder =
                    mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                builder.addTarget(surface)
                mCameraDevice!!.createCaptureSession(
                    listOf(surface, mImageReader!!.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {}

                        override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                            mCaptureSession = cameraCaptureSession
                            try {
                                mCaptureSession.setRepeatingRequest(builder.build(), null, mBackgroundHandler)
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

        fun closeCamera() {
            mCameraDevice!!.close()
            mCameraDevice = null
        }

        fun makePhoto() {
            try {
                val captureBuilder: CaptureRequest.Builder =
                    mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                captureBuilder.addTarget(mImageReader!!.surface)
                var captureCallback = object : CameraCaptureSession.CaptureCallback() {}
                mCaptureSession.stopRepeating()
                mCaptureSession.abortCaptures()
                mCaptureSession.capture(captureBuilder.build(), captureCallback, mBackgroundHandler)
            } catch (e: CameraAccessException) {
                e.printStackTrace()
            }
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
}