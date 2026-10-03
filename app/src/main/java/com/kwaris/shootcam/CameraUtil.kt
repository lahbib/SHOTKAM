package com.kwaris.shootcam

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size

object CameraUtil {
    fun backCameraId(mgr: CameraManager): String? =
        mgr.cameraIdList.firstOrNull {
            mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }

    /** Largest 16:9 size whose height is <= targetHeight. */
    fun chooseSize(sizes: Array<Size>?, targetHeight: Int): Size {
        if (sizes.isNullOrEmpty()) return Size(1280, 720)
        val wide = sizes.filter { it.width * 9 == it.height * 16 && it.height <= targetHeight }
        wide.maxByOrNull { it.height }?.let { return it }
        val any = sizes.filter { it.height <= targetHeight }
        any.maxByOrNull { it.width * it.height }?.let { return it }
        return sizes.minBy { it.width * it.height }
    }

    fun sensorOrientation(mgr: CameraManager): Int {
        val id = backCameraId(mgr) ?: return 90
        return mgr.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    }

    fun sizeFor(mgr: CameraManager, klass: Class<*>, targetHeight: Int): Size {
        val id = backCameraId(mgr) ?: return Size(1280, 720)
        val map = mgr.getCameraCharacteristics(id)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        return chooseSize(map?.getOutputSizes(klass), targetHeight)
    }
}
