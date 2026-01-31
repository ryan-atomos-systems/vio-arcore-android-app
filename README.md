# VIO ARCore Logger (Basalt-compatible)

This Android app logs ARCore frames, poses, and IMU data into a Basalt-friendly folder layout for offline VIO.

## Build & Install

1. Open the project in Android Studio.
2. Build/run on an ARCore-supported device (Android 7.0+).
3. Grant camera permission when prompted.

## Logging Output

On **Start**, a run folder is created at:

```
/Android/data/<package>/files/runs/<timestamp>_run/
```

Subfolders:

```
cam0/data/        # JPEG images
cam0/data.csv     # image timestamps + filenames
imu0/data.csv     # IMU samples (t_ns, wx, wy, wz, ax, ay, az)
arcore/           # ARCore metadata
```

Key files:

- `arcore/intrinsics.json`: camera intrinsics (`fx`, `fy`, `cx`, `cy`, `width`, `height`).
- `arcore/pose_sensor.csv`: ARCore sensor pose per frame.
- `arcore/pose_camera.csv`: ARCore camera pose per frame.
- `arcore/time_sync.csv`: timestamp offset debug info.
- `arcore/frame_timestamps.csv`: frame timestamp + Android camera timestamp.

## Pull Dataset via ADB

```
adb shell ls /sdcard/Android/data/com.example.vioarcore/files/runs/
adb pull /sdcard/Android/data/com.example.vioarcore/files/runs/<timestamp>_run/ ./run_dump/
```

## Basalt Notes

- `cam0/data.csv` uses `timestamp_ns,filename` rows.
- `imu0/data.csv` is in nanoseconds with angular velocity (rad/s) and acceleration (m/s^2).
- Use `arcore/intrinsics.json` to populate camera intrinsics in Basalt config.

If you need a full Basalt dataset config, ensure the camera model matches the intrinsics and adjust any coordinate frame conversions as needed for your pipeline.
