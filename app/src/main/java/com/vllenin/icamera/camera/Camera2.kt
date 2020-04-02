package com.vllenin.icamera.camera

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.Image
import android.media.ImageReader
import android.os.Build.VERSION_CODES
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View.OnTouchListener
import android.widget.RelativeLayout
import com.vllenin.icamera.camera.CameraUtils.CalculationSize.IMAGE
import com.vllenin.icamera.camera.CameraUtils.CalculationSize.PREVIEW
import com.vllenin.icamera.camera.ICamera.CameraFace
import com.vllenin.icamera.common.BitmapUtils
import com.vllenin.icamera.common.DebugLog
import com.vllenin.icamera.common.FileUtils
import com.vllenin.icamera.view.AutoFitTextureView
import com.vllenin.icamera.view.FaceBorderView
import java.util.concurrent.Semaphore
import kotlin.math.max
import kotlin.math.min

@TargetApi(VERSION_CODES.LOLLIPOP)
@SuppressLint("MissingPermission")
class Camera2(
  private val context: Context,
  private val textureView: AutoFitTextureView,
  private val faceBorderView: FaceBorderView
) : ICamera {

  companion object {
    private const val FOCUS_SIZE = 200
    private const val FOCUS_TAG = "FOCUS_TAG"
    private const val DISTANCE_REDRAW = 25
  }

  private var cameraId: String = ""
  private var cameraDevice: CameraDevice? = null
  private var cameraCharacteristics: CameraCharacteristics? = null
  private var cameraCaptureSession: CameraCaptureSession? = null
  private var previewRequestBuilder: CaptureRequest.Builder? = null
  private var takePictureCallbacks: ICamera.TakePictureCallbacks? = null
  private var rectFocus = Rect()
  private var countTaskTakePicture = 0
  private var isForceStop = false
  private var isLandscape = false
  private var isBurstMode = false

  private val takePictureImageLock = Semaphore(1)

  private lateinit var sensorArraySize: Size
  private lateinit var imageReader: ImageReader
  private lateinit var backgroundThread: HandlerThread
  private lateinit var backgroundHandler: Handler
  private lateinit var takePictureRunnable: Runnable
  private lateinit var mainHandler: Handler

  private val orientationEventListener =
    object : OrientationEventListener(context) {
      override fun onOrientationChanged(orientation: Int) {
        isLandscape = if (orientation in 0..44 || orientation in 315..359) {
          false
        } else if (orientation in 45..134) {
          true
        } else orientation !in 135..224
      }
    }

  private val onTouchPreviewListener = OnTouchListener { view, event ->
    // Convert coordinate when touch on preview to coordinate on sensor
    val focusX = (event.y / view.height.toFloat() * sensorArraySize.width.toFloat()).toInt()
    val focusY = ((1 - event.x / view.width.toFloat()) * sensorArraySize.height.toFloat()).toInt()

    val left = max(focusX - FOCUS_SIZE, 0)
    val top = max(focusY - FOCUS_SIZE, 0)
    val right = min(focusX + FOCUS_SIZE, sensorArraySize.width)
    val bottom = min(focusY + FOCUS_SIZE, sensorArraySize.height)
    rectFocus = Rect(left, top, right, bottom)
    focusTo(rectFocus)

    true
  }

  /** ---------------------------------- Setup callbacks ------------------------------------ */

  private val cameraStateCallback = object : CameraDevice.StateCallback() {
    override fun onOpened(camera: CameraDevice) {
      cameraDevice = camera
      previewCamera()
    }

    override fun onDisconnected(cameraDevice: CameraDevice) {}

    override fun onError(cameraDevice: CameraDevice, p1: Int) {
      closeCamera()
    }

    override fun onClosed(camera: CameraDevice) {
      super.onClosed(camera)
      if (::backgroundThread.isInitialized) {
        backgroundThread.quitSafely()
      }
    }

  }

  private val cameraSessionStateCallbackForPreview =
    object : CameraCaptureSession.StateCallback() {
      override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {}

      override fun onConfigured(captureSession: CameraCaptureSession) {
        cameraCaptureSession = captureSession
        previewRequestBuilder?.let {
          cameraCaptureSession?.setRepeatingRequest(it.build(), captureCallback, backgroundHandler)
        }
      }
    }

  private val imageReaderAvailableListener = ImageReader.OnImageAvailableListener { imageReader ->
    if (!isForceStop) {
      val image: Image
      try {
        image = imageReader.acquireLatestImage()
      } catch (e: Exception) {
        takePictureImageLock.release()
        takePictureCallbacks?.takePictureFailed(e)
        return@OnImageAvailableListener
      }

      backgroundHandler.post {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        try {
          val previewSize = Size(((textureView.parent as RelativeLayout).width),
            ((textureView.parent as RelativeLayout).height))
          val configureBitmap = BitmapUtils.configureBitmap(bytes, previewSize)
          takePictureCallbacks?.takePictureSucceeded(configureBitmap, isBurstMode)
          FileUtils.saveImageJPEGIntoFolderMedia(context, configureBitmap,
            "Vllenin-${System.currentTimeMillis()}")
          Log.d("XXX", "Save image finished")
        } catch (e: Exception) {
          takePictureCallbacks?.takePictureFailed(e)
        } finally {
          takePictureImageLock.release()
          buffer.clear()
          image.close()
        }
      }
    }
  }

  private val captureCallback =
    object : CameraCaptureSession.CaptureCallback() {

    override fun onCaptureCompleted(
      session: CameraCaptureSession,
      request: CaptureRequest,
      result: TotalCaptureResult
    ) {
      val facesArray = result[CaptureResult.STATISTICS_FACES]
      facesArray?.let {
        val boundsArray = Array<Rect>(facesArray.size) {
          facesArray[it].bounds
        }
        if (!boundsArray.isNullOrEmpty()) {
          if (rectFocus.centerY() < boundsArray[0].centerY() - DISTANCE_REDRAW ||
            rectFocus.centerY() > boundsArray[0].centerY() + DISTANCE_REDRAW ||
            rectFocus.centerX() < boundsArray[0].centerX() - DISTANCE_REDRAW ||
            rectFocus.centerX() > boundsArray[0].centerX() + DISTANCE_REDRAW ||
            faceBorderView.isFadeOut) {

            rectFocus = boundsArray[0]
            focusTo(boundsArray[0])

            val arrayRectWillDrawOnPreview = ArrayList<RectF>()
            boundsArray.forEach { boundsOnSensor ->
              val centerY: Float
              val centerX: Float
              val previewX: Float
              val previewY: Float
              var ratioFace = 1.3f

              if (cameraId.contains("1")) {// front camera
                centerY = (1.0f - boundsOnSensor.centerX().toFloat() / sensorArraySize.width) *
                  textureView.height
                centerX = (1.0f - boundsOnSensor.centerY().toFloat() / sensorArraySize.height) *
                  textureView.width
                previewX = centerX
                previewY = centerY
                ratioFace = 1f
              } else {
                // This formula is formula at onTouchPreviewListener in this class
                centerY = ((boundsOnSensor.centerX().toFloat() * textureView.height.toFloat()) /
                  sensorArraySize.width.toFloat())
                centerX = ((1.0f - boundsOnSensor.centerY().toFloat() / sensorArraySize.height.toFloat()) *
                  (textureView.width.toFloat()))
                /**
                 * Workaround: Because i don't know why with this formula, i convert coordinate on
                 * preview to coordinate on sensor is correct, but in here is incorrect.
                 * If someone resolve this issues, please push your branch to git.
                 */
                val plusX = (centerX - (textureView.width/2)) * (textureView.width.toFloat() /
                  sensorArraySize.height)
                /**
                 * Because preview is center crop, if above crop then "plusY = 0". Search:
                 * layoutParamsView.addRule(RelativeLayout.CENTER_IN_PARENT) in class CameraView
                 */
                val plusY = (textureView.height/2 - textureView.height/2)

                previewX = centerX + plusX.toInt()
                previewY = centerY - plusY
              }

              val widthFace: Float
              val heightFace: Float
              if (!isLandscape) {
                widthFace = (boundsOnSensor.width().toFloat() * textureView.height.toFloat() /
                  sensorArraySize.width * ratioFace)
                heightFace = (boundsOnSensor.height().toFloat() * textureView.width.toFloat() /
                  sensorArraySize.height * ratioFace)
              } else {
                widthFace = (boundsOnSensor.height().toFloat() * textureView.width.toFloat() /
                  sensorArraySize.height * ratioFace)
                heightFace = (boundsOnSensor.width().toFloat() * textureView.height.toFloat() /
                  sensorArraySize.width * ratioFace)
              }

              val boundsOnPreview = RectF(previewX - heightFace/2, previewY - widthFace/2,
                previewX + heightFace/2, previewY + widthFace/2)
              arrayRectWillDrawOnPreview.add(boundsOnPreview)
            }
            mainHandler.post { faceBorderView.drawListFace(arrayRectWillDrawOnPreview.toTypedArray()) }
          }
        } else if (boundsArray.isNullOrEmpty()) {
          mainHandler.post { faceBorderView.fadeOut() }
        }
      }

      if (request.tag == FOCUS_TAG) {
        try {
          previewRequestBuilder?.setTag(null)
          previewRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER,
            CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
          previewRequestBuilder?.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
            CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
          previewRequestBuilder?.let {
            cameraCaptureSession?.setRepeatingRequest(it.build(), this, backgroundHandler)
          }
        } catch (e: Exception) {
          DebugLog.e("Focus failed onCaptureCompleted ${e.message}")
        }
      }
    }
  }

  /** --------------------------------------------------------------------------------------------- **/

  override fun openCamera(cameraFace: CameraFace) {
    initBackgroundThread()

    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    cameraId = if (cameraFace == CameraFace.BACK) { "0" } else { "1" }
    cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
    sensorArraySize = cameraCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)!!
    val sensorOrientation = cameraCharacteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION)
    val mapConfig =
      cameraCharacteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
    val previewSize = CameraUtils.calculationSize(mapConfig, PREVIEW)

    if (sensorOrientation == 90 || sensorOrientation == 270) {
      textureView.setAspectRatio(previewSize.height, previewSize.width)
    } else {
      textureView.setAspectRatio(previewSize.width, previewSize.height)
    }

    try {
      cameraManager.openCamera(cameraId, cameraStateCallback, backgroundHandler)
    } catch (e: CameraAccessException) {
      DebugLog.e(e.message ?: "")
    }
  }

  override fun switchCamera() {
    closeCamera()
    if (cameraId.contains("0")) {
      openCamera(CameraFace.FRONT)
    } else {
      openCamera(CameraFace.BACK)
    }
  }

  override fun capture(takePictureCallbacks: ICamera.TakePictureCallbacks, delayMs: Int) {
    this.takePictureCallbacks = takePictureCallbacks
    isBurstMode = false
    isForceStop = false
    takePictureImageLock.release()

    takePicture()
  }

  override fun captureBurst(takePictureCallbacks: ICamera.TakePictureCallbacks, delayMs: Int) {
    this.takePictureCallbacks = takePictureCallbacks
    isBurstMode = true
    isForceStop = false
    takePictureImageLock.release()
    countTaskTakePicture = 0
    takePictureRunnable = Runnable {
      Log.d("XXX", "Running ~~~~~~~~~~~~~~~")
      if (!isForceStop) {
        mainHandler.post {
          takePictureImageLock.acquire()
          countTaskTakePicture++
          Log.d("XXX", "countTaskTakePicture $countTaskTakePicture")
          takePicture()
          backgroundHandler.postDelayed(takePictureRunnable, 100)
        }
      }
    }
    backgroundHandler.post(takePictureRunnable)
  }

  override fun stopCaptureBurst() {
    takePictureImageLock.release()
    backgroundHandler.removeCallbacks(takePictureRunnable)
    isForceStop = true
    takePictureCallbacks = null
  }

  override fun captureBurstFreeHand(takePictureCallbacks: ICamera.TakePictureCallbacks, delayMs: Int) {
    this.takePictureCallbacks = takePictureCallbacks
    isBurstMode = true
    countTaskTakePicture = 0
    isForceStop = false

  }

  override fun closeCamera() {
    try {
      orientationEventListener.disable()
      cameraCaptureSession?.close()
      cameraCaptureSession = null
      cameraDevice?.close()
      cameraDevice = null
      imageReader.close()
      quitBackgroundThread()
    } catch (e: CameraAccessException) {
      DebugLog.e(e.message ?: " When closeCamera")
    } catch (e: InterruptedException) {
      DebugLog.e(e.message ?: " When closeCamera")
    } catch (e: Exception) {
      DebugLog.e(e.message ?: " When closeCamera")
    }
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun previewCamera() {
    orientationEventListener.enable()
    val mapConfig = cameraCharacteristics?.get(
      CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    val imageSize = CameraUtils.calculationSize(mapConfig!!, IMAGE)
    textureView.surfaceTexture.setDefaultBufferSize(imageSize.width, imageSize.height)
    textureView.setOnTouchListener(onTouchPreviewListener)
    val surface = Surface(textureView.surfaceTexture)
    imageReader = ImageReader.newInstance(imageSize.width, imageSize.height, ImageFormat.JPEG, 2)
    imageReader.setOnImageAvailableListener(imageReaderAvailableListener, backgroundHandler)

    previewRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
    previewRequestBuilder?.addTarget(surface)
    previewRequestBuilder?.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
    // Face detection with mode MODE_SIMPLE, can change to STATISTICS_FACE_DETECT_MODE_FULL
    previewRequestBuilder?.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE,
      CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE)
    if (!rectFocus.isEmpty) {
      focusTo(rectFocus)
    }

    cameraDevice?.createCaptureSession(listOf(surface, imageReader.surface),
      cameraSessionStateCallbackForPreview, backgroundHandler)
  }

  private fun takePicture() {
    Log.d("XXX", "takePicture")
    val takePictureRequestBuilder =
      cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
    takePictureRequestBuilder?.addTarget(imageReader.surface)
    takePictureRequestBuilder?.let {
      cameraCaptureSession?.capture(it.build(), object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession,
          request: CaptureRequest,
          result: TotalCaptureResult) {

        }
      }, null)
    }
  }

  private fun initBackgroundThread() {
    backgroundThread = HandlerThread("Camera2")
    backgroundThread.start()
    backgroundHandler = Handler(backgroundThread.looper)

    mainHandler = Handler(Looper.getMainLooper())
  }

  private fun quitBackgroundThread() {
    backgroundHandler.looper.quitSafely()
    backgroundThread.join()
  }

  private fun focusTo(rect: Rect) {
    try {
      cameraCaptureSession?.stopRepeating()
      previewRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER,
        CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
      previewRequestBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
      val meteringRectangle = MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX)

      if (isMeteringAreaAFSupported()) {
        previewRequestBuilder?.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRectangle))
        previewRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER,
          CameraMetadata.CONTROL_AF_TRIGGER_START)
        previewRequestBuilder?.set(CaptureRequest.CONTROL_AF_MODE,
          CameraMetadata.CONTROL_AF_MODE_AUTO)
      }
      if (isMeteringAreaAESupported()) {
        previewRequestBuilder?.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRectangle))
        previewRequestBuilder?.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
          CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
      }
      previewRequestBuilder?.setTag(FOCUS_TAG)
      previewRequestBuilder?.let {
        cameraCaptureSession?.capture(it.build(), captureCallback, backgroundHandler)
      }
    } catch (e: CameraAccessException) {
      DebugLog.e("Focus failed ${e.message}")
    } catch (e: IllegalStateException) {
      DebugLog.e("Focus failed ${e.message}")
    }
  }

  private fun isMeteringAreaAESupported(): Boolean {
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val cameraCharacteristic = cameraManager.getCameraCharacteristics(cameraId)
    val maxRegionsAE = cameraCharacteristic.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE)
      ?: return false
    return maxRegionsAE >= 1
  }

  private fun isMeteringAreaAFSupported(): Boolean {
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val cameraCharacteristic = cameraManager.getCameraCharacteristics(cameraId)
    val maxRegionsAF = cameraCharacteristic.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)
      ?: return false
    return maxRegionsAF >= 1
  }

}