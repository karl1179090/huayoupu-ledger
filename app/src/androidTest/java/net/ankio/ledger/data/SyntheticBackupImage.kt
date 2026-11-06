package net.ankio.ledger.data

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/** Tiny real images keep backup/media tests synthetic while exercising decoder validation. */
internal fun syntheticBackupImage(seed:Int):ByteArray {
    val bitmap=Bitmap.createBitmap(3,3,Bitmap.Config.ARGB_8888)
    return try { bitmap.eraseColor(0xff000000.toInt() or seed)
        ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }.toByteArray()
    } finally { bitmap.recycle() }
}
