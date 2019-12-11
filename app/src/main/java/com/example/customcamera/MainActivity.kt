package com.example.customcamera

import android.Manifest
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
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v4.content.ContextCompat.checkSelfPermission
import android.support.v7.app.AppCompatActivity
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.Permission


class MainActivity : AppCompatActivity() {
    var mBackgroundThread: HandlerThread? = null
    var mBackgroundHandler: Handler? = null

    var myCameras = arrayListOf<CameraService>()
    var mCameraManager: CameraManager? = null
    private var appContext: Context? = this
    private var cameraId = ""
    private var camera1 = 0
    private var camera2 = 1

//TODO Обработка варианта поворота экрана, чтоб не крашился

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
            || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        } else {
            mCameraManager =
                appContext?.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraId = getCameraWithFacing(mCameraManager!!, LENS_FACING_BACK)
            for (camera in mCameraManager!!.cameraIdList) {
                myCameras.add(CameraService(mCameraManager!!, camera, this))
            }
            if (myCameras[camera1] != null)
                if (!myCameras[camera1].isOpen)
                    myCameras[camera1].openCamera()
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
            // Получения списка выходного формата, который поддерживает камера
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: continue
            //  Определение какая камера куда смотрит
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing != null && facing == lensFacing) {
                myCameraId = cameraId
            }

            val sizesJPEG = map.getOutputSizes(ImageFormat.JPEG)

            sizesJPEG?.let {
                for (item in sizesJPEG) {
                    Log.d("Camera", "w:" + item.width + " h:" + item.height)
                }
//                w:2592 h:1936
            }

            possibleCandidate =
                myCameraId  //just in case device don't have any camera with given facing
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
        private var file: File = File(Environment.DIRECTORY_DCIM, "your best photo.jpg")

        private val mOnImageAvailableListener: ImageReader.OnImageAvailableListener =
            ImageReader.OnImageAvailableListener { reader ->
                mBackgroundHandler!!.post(ImageSaver(reader.acquireNextImage(), file))
            }


        val isOpen: Boolean
            get() = mCameraDevice != null

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
                            Log.d("Camera", "Open camera  with id:" + mCameraDevice!!.id)
                            createCameraPreviewSession()
                        }

                        override fun onDisconnected(p0: CameraDevice) {
                            mCameraDevice!!.close()
                            Log.d("Camera", "disconnect camera  with id:" + mCameraDevice!!.id)
//                        mCameraDevice = null
                        }

                        override fun onError(cameraDevice: CameraDevice, error: Int) {
                            Log.d(
                                "Camera",
                                "Error!!! camera id:" + cameraDevice.id + " error:" + error
                            )
                        }

                    }
                    mCameraManager.openCamera(mCameraID, mCameraCallback, mBackgroundHandler)
                }
            } catch (e: CameraAccessException) {
            }
        }

        private fun createCameraPreviewSession() {
            mImageReader = ImageReader.newInstance(2592, 1936, ImageFormat.JPEG, 1)
            mImageReader!!.setOnImageAvailableListener(mOnImageAvailableListener, null)


            //texture_view = activity.findViewById<TextureView>(R.id.texture_view)
            var texture: SurfaceTexture = texture_view.surfaceTexture
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
//            mCameraDevice = null

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


























    //    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        setContentView(R.layout.activity_main)
////        texture_view.surfaceTextureListener = object: TextureView.SurfaceTextureListener {
////            override fun onSurfaceTextureDestroyed(p0: SurfaceTexture?): Boolean {
////                TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
////            }
////
////            override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture?, p1: Int, p2: Int) { }
////
////            override fun onSurfaceTextureUpdated(p0: SurfaceTexture?) { }
////
////            override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int) {
////                surface?.let {
////                    mOnSurfaceTextureAvailable.onNext(surface)
////                }
////            }
////        }
//    }
//    fun openCamera(
//        cameraId: String,
//        cameraManager: CameraManager
//    ): Observable<Pair<DeviceStateEvents, CameraDevice>> {
//        return Observable.create { observableEmitter ->
//            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
//                override fun onOpened(cameraDevice: CameraDevice) {
//                    observableEmitter.onNext(Pair(DeviceStateEvents.ON_OPENED, cameraDevice))
//                }
//
//                override fun onClosed(cameraDevice: CameraDevice) {
//                    observableEmitter.onNext(Pair(DeviceStateEvents.ON_CLOSED, cameraDevice))
//                    observableEmitter.onComplete()
//                }
//
//                override fun onDisconnected(cameraDevice: CameraDevice) {
//                    observableEmitter.onNext(Pair(DeviceStateEvents.ON_DISCONNECTED, cameraDevice))
//                    observableEmitter.onComplete()
//                }
//
//                override fun onError(camera: CameraDevice, error: Int) {
//                    observableEmitter.onError(
//                        OpenCameraException(OpenCameraException.Reason.getReason(error))
//                    )
//                }
//            }, null)
//        }
//    }
//
//    fun createCaptureSession(
//        cameraDevice: CameraDevice, surfaceList: List<Surface>
//    ): Observable<Pair<CaptureSessionStateEvents, CameraCaptureSession>> {
//        return Observable.create { observableEmitter ->
//            cameraDevice.createCaptureSession(
//                surfaceList,
//                object : CameraCaptureSession.StateCallback() {
//
//                    override fun onConfigured(session: CameraCaptureSession) {
//                        observableEmitter.onNext(Pair(CaptureSessionStateEvents.ON_CONFIGURED, session))
//                    }
//
//                    override fun onConfigureFailed(session: CameraCaptureSession) {
//                        observableEmitter.onError(CreateCaptureSessionException(session))
//                    }
//
//                    override fun onReady(session: CameraCaptureSession) {
//                        observableEmitter.onNext(Pair(CaptureSessionStateEvents.ON_READY, session))
//                    }
//
//                    override fun onActive(session: CameraCaptureSession) {
//                        observableEmitter.onNext(Pair(CaptureSessionStateEvents.ON_ACTIVE, session))
//                    }
//
//                    override fun onClosed(session: CameraCaptureSession) {
//                        observableEmitter.onNext(Pair(CaptureSessionStateEvents.ON_CLOSED, session))
//                        observableEmitter.onComplete()
//                    }
//
//                    override fun onSurfacePrepared(session: CameraCaptureSession, surface: Surface) {
//                        observableEmitter.onNext(Pair(CaptureSessionStateEvents.ON_SURFACE_PREPARED, session))
//                    }
//                },
//                null
//            )
//        }
//    }
//
//    private fun createCaptureCallback(observableEmitter: ObservableEmitter<CaptureSessionData>): CameraCaptureSession.CaptureCallback {
//        return object : CameraCaptureSession.CaptureCallback() {
//
//            override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {}
//
//            override fun onCaptureProgressed(session: CameraCaptureSession, request: CaptureRequest, partialResult: CaptureResult) { }
//
//            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
//                if (!observableEmitter.isDisposed) {
//                    observableEmitter.onNext(CaptureSessionData(CaptureSessionEvents.ON_COMPLETED, session, request, result))
//                }
//            }
//
//            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
//                if (!observableEmitter.isDisposed) {
//                    observableEmitter.onError(CameraCaptureFailedException(failure))
//                }
//            }
//
//            override fun onCaptureSequenceCompleted(session: CameraCaptureSession, sequenceId: Int, frameNumber: Long) { }
//
//            override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {}
//        }
//    }
//
//    fun fromSetRepeatingRequest(captureSession: CameraCaptureSession , request: CaptureRequest): Observable<CaptureSessionData> {
//        return Observable
//            .create { observableEmitter -> captureSession .setRepeatingRequest(request, createCaptureCallback(observableEmitter), null)}
//    }
//
//    fun fromCapture(captureSession: CameraCaptureSession, request: CaptureRequest): Observable<CaptureSessionData> {
//        return Observable
//            .create { observableEmitter -> captureSession.capture(request, createCaptureCallback(observableEmitter), null)}
//    }
//
//    private fun setupSurface(surfaceTexture: SurfaceTexture) {
//        surfaceTexture.setDefaultBufferSize(
//            mCameraParams.previewSize.getWidth(),
//            mCameraParams.previewSize.getHeight()
//        )
//        mSurface = Surface(surfaceTexture)
//    }
//
//    fun createOnImageAvailableObservable(imageReader: ImageReader): Observable<ImageReader> {
//        return Observable.create<ImageReader> { subscriber ->
//
//            val listener  = { reader: ImageReader ->
//                if (!subscriber.isDisposed) {
//                    subscriber.onNext(reader)
//                }
//            }
//            imageReader.setOnImageAvailableListener(listener, null)
//            subscriber.setCancellable {
//                imageReader.setOnImageAvailableListener(null, null)
//            }
//        }
//    }
//
//    fun save(image: Image, file: File): Single<File> {
//        return Single.fromCallable {
//            image.use { image ->
//                var output: FileChannel = FileOutputStream(file).channel
//                output.write(image.planes[0].buffer)
//                file
//            }
//        }
//    }
//
//    private fun initImageReader() {
//    var sizeForImageReader: Size = CameraStrategy.getStillImageSize(mCameraParams.cameraCharacteristics, mCameraParams.previewSize);
//    mImageReader = ImageReader.newInstance(sizeForImageReader.getWidth(), sizeForImageReader.getHeight(), ImageFormat.JPEG, 1);
//    mCompositeDisposable.add(
//        ImageSaverRxWrapper.createOnImageAvailableObservable(mImageReader)
//            .observeOn(Schedulers.io())
//            .flatMap(imageReader -> ImageSaverRxWrapper.save(imageReader.acquireLatestImage(), mFile).toObservable())
//            .observeOn(AndroidSchedulers.mainThread())
//            .subscribe(file -> mCallback.onPhotoTaken(file.getAbsolutePath(), getLensFacingPhotoType()))
//    );
//}


//}
