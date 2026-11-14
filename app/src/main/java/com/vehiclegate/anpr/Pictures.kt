package com.vehiclegate.anpr

import java.io.File

/**
 * Stored pictures of a vehicle: a small scene picture (≤640 px, plate boxed) at the stored path,
 * and the plate crop next to it ("…_plate.jpg"). In "plate crop only" mode the stored path is the
 * plate crop itself.
 */
object Pictures {
    fun plateFile(path: String?): File? {
        if (path == null) return null
        val f = File(path.removeSuffix(".jpg") + "_plate.jpg")
        return if (f.exists()) f else null
    }

    /** Best small picture for a list: the plate crop if there is one, else the stored picture. */
    fun thumbPath(path: String?): String? = plateFile(path)?.absolutePath ?: path

    fun delete(path: String?) {
        if (path == null) return
        File(path).delete()
        File(path.removeSuffix(".jpg") + "_plate.jpg").delete()
    }
}
