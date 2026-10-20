package com.smartcooking.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.smartcooking.app.core.AppJson
import com.smartcooking.app.core.KeyValueStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream

/** One recognised candidate awaiting user confirmation; survives process death as a local draft. */
@Serializable
data class DraftItem(
    val form: InventoryForm,
    val freshnessDetail: JsonObject? = null,
    val freshnessLabel: String? = null,
    val imageUrl: String? = null,
    val source: String = "online",
)

@Serializable
private data class DraftFile(val version: Int = 1, val user: String, val items: List<DraftItem>)

class RecognitionDrafts(private val store: KeyValueStore) {
    private fun key(user: String) = "cookx:recognition:v2:$user"

    fun save(user: String, items: List<DraftItem>) {
        if (!store.putSync(key(user), AppJson.encodeToString(DraftFile.serializer(), DraftFile(1, user, items)))) {
            throw IllegalStateException("识别草稿保存失败，请检查本地存储")
        }
    }

    fun load(user: String): List<DraftItem> = runCatching {
        val file = AppJson.decodeFromString(DraftFile.serializer(), store.get(key(user)) ?: return emptyList())
        if (file.user == user && file.version == 1) file.items else emptyList()
    }.getOrDefault(emptyList())

    fun clear(user: String) = store.removeSync(key(user))
}

/** Decodes a picked/captured photo, applies EXIF rotation and re-encodes a bounded JPEG for upload. */
fun loadUploadJpeg(context: Context, uri: Uri, maxDimension: Int = 1600, quality: Int = 88): ByteArray {
    val resolver = context.contentResolver
    // A picked or captured photo may be unreadable (revoked grant, deleted file): report that plainly instead of a raw exception.
    fun open() = runCatching { resolver.openInputStream(uri) }.getOrNull() ?: throw IllegalStateException("无法读取图片")
    // inJustDecodeBounds always returns a null bitmap, so the size — not the return value — tells whether the bytes are an image.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open().use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalStateException("图片格式不受支持")
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxDimension || bounds.outHeight / (sample * 2) >= maxDimension) sample *= 2
    val bitmap = open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        ?: throw IllegalStateException("图片格式不受支持")
    val rotation = runCatching {
        resolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
    }.getOrDefault(0f)
    val scale = minOf(1f, maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height))
    val matrix = Matrix().apply { postRotate(rotation); postScale(scale, scale) }
    val output = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    return ByteArrayOutputStream().use { out ->
        output.compress(Bitmap.CompressFormat.JPEG, quality, out)
        out.toByteArray()
    }
}
