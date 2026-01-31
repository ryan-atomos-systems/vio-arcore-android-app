package com.example.vioarcore

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer, SensorEventListener {
    private lateinit var surfaceView: GLSurfaceView
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private var session: Session? = null
    private var isResumed = false

    private val loggingEnabled = AtomicBoolean(false)
    private val droppedFrames = AtomicInteger(0)
    private val frameCounter = AtomicInteger(0)
    private val imuCounter = AtomicInteger(0)

    private var runFolder: File? = null
    private var camCsvWriter: BufferedWriter? = null
    private var imuCsvWriter: BufferedWriter? = null
    private var poseSensorWriter: BufferedWriter? = null
    private var poseCameraWriter: BufferedWriter? = null
    private var timeSyncWriter: BufferedWriter? = null
    private var frameTsWriter: BufferedWriter? = null

    private var ioThread: Thread? = null
    private val ioQueue = ArrayBlockingQueue<LogTask>(32)
    private val ioRunning = AtomicBoolean(false)

    private var intrinsicsLogged = false
    private var lastImageGlobalNs: Long = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastStatusUpdateNs = 0L
    private var lastFrameCount = 0
    private var lastImuCount = 0

    private lateinit var sensorManager: SensorManager
    private var gyroValues = FloatArray(3)
    private var accelValues = FloatArray(3)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surface_view)
        statusText = findViewById(R.id.text_status)
        startButton = findViewById(R.id.button_start)
        stopButton = findViewById(R.id.button_stop)

        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setRenderer(this)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        startButton.setOnClickListener { startLogging() }
        stopButton.setOnClickListener { stopLogging() }

        updateStatusText()
    }

    override fun onResume() {
        super.onResume()
        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 0)
            return
        }
        if (session == null) {
            session = Session(this)
            val config = Config(session)
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            session?.configure(config)
        }
        try {
            session?.resume()
            surfaceView.onResume()
            isResumed = true
        } catch (e: Exception) {
            statusText.text = "Failed to resume ARCore session: ${e.message}"
        }
    }

    override fun onPause() {
        super.onPause()
        if (loggingEnabled.get()) {
            stopLogging()
        }
        isResumed = false
        surfaceView.onPause()
        session?.pause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasCameraPermission()) {
            onResume()
        } else {
            statusText.text = "Camera permission required."
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val currentSession = session ?: return
        if (!isResumed) return

        val frame = try {
            currentSession.update()
        } catch (e: Exception) {
            return
        }

        if (loggingEnabled.get()) {
            handleFrame(frame)
        }

        maybeUpdateStatus()
    }

    private fun handleFrame(frame: Frame) {
        val frameTimestampNs = frame.timestamp
        val elapsedNowNs = SystemClock.elapsedRealtimeNanos()
        val offsetNs = elapsedNowNs - frameTimestampNs
        val globalTimestampNs = frameTimestampNs + offsetNs

        if (!intrinsicsLogged) {
            intrinsicsLogged = logIntrinsics(frame)
        }

        val sensorPose = try {
            frame.androidSensorPose
        } catch (e: Exception) {
            Pose.IDENTITY
        }
        val cameraPose = frame.camera.pose
        val cameraTimestampNs = try {
            frame.androidCameraTimestamp
        } catch (e: Exception) {
            -1L
        }

        val imageTask = if (shouldCaptureImage(globalTimestampNs)) {
            tryAcquireImageTask(frame, globalTimestampNs)
        } else {
            null
        }

        val logTask = LogTask.FrameData(
            frameTimestampNs = frameTimestampNs,
            elapsedNowNs = elapsedNowNs,
            offsetNs = offsetNs,
            globalTimestampNs = globalTimestampNs,
            cameraTimestampNs = cameraTimestampNs,
            sensorPose = sensorPose,
            cameraPose = cameraPose,
            imageTask = imageTask
        )

        if (!ioQueue.offer(logTask)) {
            droppedFrames.incrementAndGet()
            imageTask?.close()
        } else {
            frameCounter.incrementAndGet()
        }
    }

    private fun shouldCaptureImage(globalTimestampNs: Long): Boolean {
        val intervalNs = 1_000_000_000L / 30L
        return globalTimestampNs - lastImageGlobalNs >= intervalNs
    }

    private fun tryAcquireImageTask(frame: Frame, globalTimestampNs: Long): ImageTask? {
        return try {
            val image = frame.acquireCameraImage()
            lastImageGlobalNs = globalTimestampNs
            ImageTask(image, globalTimestampNs)
        } catch (e: Exception) {
            null
        }
    }

    private fun logIntrinsics(frame: Frame): Boolean {
        val intrinsics = frame.camera.imageIntrinsics
        val focalLength = intrinsics.focalLength
        val principalPoint = intrinsics.principalPoint
        val json = """
            {
              \"fx\": ${focalLength[0]},
              \"fy\": ${focalLength[1]},
              \"cx\": ${principalPoint[0]},
              \"cy\": ${principalPoint[1]},
              \"width\": ${intrinsics.imageDimensions[0]},
              \"height\": ${intrinsics.imageDimensions[1]}
            }
        """.trimIndent()
        val file = File(runFolder, "arcore/intrinsics.json")
        return ioQueue.offer(LogTask.TextFile(file, json))
    }

    private fun startLogging() {
        if (loggingEnabled.get()) return
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val runName = "run"
        val base = File(getExternalFilesDir(null), "runs")
        val runDir = File(base, "${timestamp}_${runName}")
        val camDir = File(runDir, "cam0/data")
        val imuDir = File(runDir, "imu0")
        val arDir = File(runDir, "arcore")
        camDir.mkdirs()
        imuDir.mkdirs()
        arDir.mkdirs()

        runFolder = runDir

        camCsvWriter = BufferedWriter(FileWriter(File(runDir, "cam0/data.csv")))
        imuCsvWriter = BufferedWriter(FileWriter(File(runDir, "imu0/data.csv")))
        poseSensorWriter = BufferedWriter(FileWriter(File(runDir, "arcore/pose_sensor.csv")))
        poseCameraWriter = BufferedWriter(FileWriter(File(runDir, "arcore/pose_camera.csv")))
        timeSyncWriter = BufferedWriter(FileWriter(File(runDir, "arcore/time_sync.csv")))
        frameTsWriter = BufferedWriter(FileWriter(File(runDir, "arcore/frame_timestamps.csv")))

        camCsvWriter?.write("timestamp_ns,filename\n")
        imuCsvWriter?.write("t_ns,wx,wy,wz,ax,ay,az\n")
        poseSensorWriter?.write("t_ns,tx,ty,tz,qx,qy,qz,qw\n")
        poseCameraWriter?.write("t_ns,tx,ty,tz,qx,qy,qz,qw\n")
        timeSyncWriter?.write("frame_ts_ns,elapsed_now_ns,offset_ns\n")
        frameTsWriter?.write("frame_ts_ns,camera_ts_ns\n")

        intrinsicsLogged = false
        droppedFrames.set(0)
        frameCounter.set(0)
        imuCounter.set(0)
        lastImageGlobalNs = 0

        startIoThread()
        registerSensors()
        loggingEnabled.set(true)

        updateStatusText()
    }

    private fun stopLogging() {
        if (!loggingEnabled.get()) return
        loggingEnabled.set(false)
        unregisterSensors()
        stopIoThread()
        closeWriters()
        updateStatusText()
    }

    private fun startIoThread() {
        ioRunning.set(true)
        ioThread = Thread {
            while (ioRunning.get() || ioQueue.isNotEmpty()) {
                val task = ioQueue.poll()
                if (task == null) {
                    Thread.sleep(2)
                    continue
                }
                when (task) {
                    is LogTask.FrameData -> handleFrameTask(task)
                    is LogTask.TextFile -> writeTextFile(task)
                    is LogTask.ImuSample -> imuCsvWriter?.write(task.line)
                }
            }
        }
        ioThread?.start()
    }

    private fun stopIoThread() {
        ioRunning.set(false)
        ioThread?.join()
    }

    private fun handleFrameTask(task: LogTask.FrameData) {
        timeSyncWriter?.write("${task.frameTimestampNs},${task.elapsedNowNs},${task.offsetNs}\n")
        frameTsWriter?.write("${task.frameTimestampNs},${task.cameraTimestampNs}\n")
        writePose(poseSensorWriter, task.globalTimestampNs, task.sensorPose)
        writePose(poseCameraWriter, task.globalTimestampNs, task.cameraPose)
        task.imageTask?.let { imageTask ->
            val filename = "${imageTask.globalTimestampNs}.jpg"
            val outputFile = File(runFolder, "cam0/data/$filename")
            writeImage(outputFile, imageTask)
            camCsvWriter?.write("${imageTask.globalTimestampNs},cam0/data/$filename\n")
        }
    }

    private fun writePose(writer: BufferedWriter?, tNs: Long, pose: Pose) {
        val t = pose.translation
        val q = pose.rotationQuaternion
        writer?.write(
            "${tNs},${t[0]},${t[1]},${t[2]},${q[0]},${q[1]},${q[2]},${q[3]}\n"
        )
    }

    private fun writeTextFile(task: LogTask.TextFile) {
        task.file.parentFile?.mkdirs()
        task.file.writeText(task.content)
    }

    private fun writeImage(file: File, imageTask: ImageTask) {
        val image = imageTask.image
        try {
            val nv21 = yuv420ToNv21(image)
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val outputStream = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 90, outputStream)
            BufferedOutputStream(FileOutputStream(file)).use { stream ->
                stream.write(outputStream.toByteArray())
            }
        } finally {
            image.close()
        }
    }

    private fun yuv420ToNv21(image: android.media.Image): ByteArray {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)

        val rowStride = uPlane.rowStride
        val pixelStride = uPlane.pixelStride
        val width = image.width
        val height = image.height
        var offset = ySize
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        val uRow = ByteArray(rowStride)
        val vRow = ByteArray(rowStride)
        for (row in 0 until chromaHeight) {
            uBuffer.position(row * rowStride)
            vBuffer.position(row * rowStride)
            uBuffer.get(uRow, 0, rowStride)
            vBuffer.get(vRow, 0, rowStride)
            var col = 0
            while (col < chromaWidth) {
                nv21[offset++] = vRow[col * pixelStride]
                nv21[offset++] = uRow[col * pixelStride]
                col++
            }
        }
        return nv21
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!loggingEnabled.get()) return
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED, Sensor.TYPE_GYROSCOPE -> {
                gyroValues = event.values.copyOfRange(0, 3)
            }
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED, Sensor.TYPE_ACCELEROMETER -> {
                accelValues = event.values.copyOfRange(0, 3)
            }
        }
        val tNs = event.timestamp
        val line = "${tNs},${gyroValues[0]},${gyroValues[1]},${gyroValues[2]},${accelValues[0]},${accelValues[1]},${accelValues[2]}\n"
        if (!ioQueue.offer(LogTask.ImuSample(line))) {
            droppedFrames.incrementAndGet()
        } else {
            imuCounter.incrementAndGet()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op.
    }

    private fun registerSensors() {
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyro?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        accel?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
    }

    private fun unregisterSensors() {
        sensorManager.unregisterListener(this)
    }

    private fun updateStatusText() {
        val runPath = runFolder?.absolutePath ?: "Not running"
        statusText.text = "Frames/s: 0\nIMU/s: 0\nDropped: ${droppedFrames.get()}\nRun: $runPath"
    }

    private fun maybeUpdateStatus() {
        val now = SystemClock.elapsedRealtimeNanos()
        if (now - lastStatusUpdateNs < 1_000_000_000L) return
        val frameCount = frameCounter.get()
        val imuCount = imuCounter.get()
        val framesPerSec = frameCount - lastFrameCount
        val imuPerSec = imuCount - lastImuCount
        lastFrameCount = frameCount
        lastImuCount = imuCount
        lastStatusUpdateNs = now
        mainHandler.post {
            val runPath = runFolder?.absolutePath ?: "Not running"
            statusText.text = "Frames/s: $framesPerSec\nIMU/s: $imuPerSec\nDropped: ${droppedFrames.get()}\nRun: $runPath"
        }
    }

    private fun closeWriters() {
        camCsvWriter?.flush()
        camCsvWriter?.close()
        imuCsvWriter?.flush()
        imuCsvWriter?.close()
        poseSensorWriter?.flush()
        poseSensorWriter?.close()
        poseCameraWriter?.flush()
        poseCameraWriter?.close()
        timeSyncWriter?.flush()
        timeSyncWriter?.close()
        frameTsWriter?.flush()
        frameTsWriter?.close()
        camCsvWriter = null
        imuCsvWriter = null
        poseSensorWriter = null
        poseCameraWriter = null
        timeSyncWriter = null
        frameTsWriter = null
    }

    sealed class LogTask {
        data class FrameData(
            val frameTimestampNs: Long,
            val elapsedNowNs: Long,
            val offsetNs: Long,
            val globalTimestampNs: Long,
            val cameraTimestampNs: Long,
            val sensorPose: Pose,
            val cameraPose: Pose,
            val imageTask: ImageTask?
        ) : LogTask()

        data class TextFile(val file: File, val content: String) : LogTask()

        data class ImuSample(val line: String) : LogTask()
    }

    data class ImageTask(val image: android.media.Image, val globalTimestampNs: Long) {
        fun close() {
            image.close()
        }
    }
}
