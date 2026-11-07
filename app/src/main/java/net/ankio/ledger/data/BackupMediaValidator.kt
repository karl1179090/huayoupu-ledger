package net.ankio.ledger.data

import android.graphics.BitmapFactory
import java.io.File

/** Preflight image bytes before any financial or screenshot-owner mutation. */
internal object BackupMediaValidator {
    fun validate(file:File) { require(file.length() in 1..LedgerArchive.MAX_IMAGE_BYTES.toLong()) { "备份图片大小无效" };validate(file.readBytes()) }
    fun validate(bytes:ByteArray) {
        require(bytes.isNotEmpty() && bytes.size<=LedgerArchive.MAX_IMAGE_BYTES) { "备份图片大小无效" }
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth>0 && bounds.outHeight>0 && bounds.outWidth.toLong()*bounds.outHeight<=50_000_000L) { "账单图片无法读取" }
        var sample=1
        while(bounds.outWidth/sample>1024 || bounds.outHeight/sample>1024)sample*=2
        requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample })) { "账单图片无法读取" }.recycle()
    }
}
