package kr.hanidayo.eodi.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.File

/** 기둥 사진에서 층(B2, 지하2층)과 구역 번호(A-12)를 읽음 */
object Ocr {
    data class Result(val lines: List<String> = emptyList(), val level: Int? = null, val pillar: String? = null)

    suspend fun recognize(context: Context, file: File): Result = runCatching {
        val image = InputImage.fromFilePath(context, Uri.fromFile(file))
        val client = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        val text = client.process(image).await()
        parse(text.textBlocks.flatMap { b -> b.lines.map { it.text } })
    }.getOrDefault(Result())

    fun parse(lines: List<String>): Result {
        val all = lines.joinToString(" ")
        val level =
            Regex("""지하\s*([1-9])\s*층""").find(all)?.let { -it.groupValues[1].toInt() }
                ?: Regex("""(?<![A-Za-z0-9])[Bb]\s*([1-9])(?![0-9])""").find(all)?.let { -it.groupValues[1].toInt() }
                ?: Regex("""(?<![0-9])([1-9])\s*(?:F|층)(?![0-9])""").find(all)?.let { it.groupValues[1].toInt() - 1 }

        val codes = Regex("""(?<![A-Za-z0-9])([A-Z]{1,2}|[가-힣])\s*-?\s*[0-9]{1,3}(?![0-9])""")
            .findAll(all).map { it.value.replace(" ", "") }
            .filterNot { Regex("""^[Bb]-?[1-9]$""").matches(it) }
            .distinct().take(3).toList()

        val pillar = if (codes.isNotEmpty()) codes.joinToString(" · ")
        else lines.filter { it.length in 1..10 }.take(2).takeIf { it.isNotEmpty() }?.joinToString(" · ")
        return Result(lines, level, pillar)
    }

    /** 카메라 사진의 EXIF 회전을 실제 픽셀에 반영 */
    fun applyExifRotation(file: File, bmp: Bitmap): Bitmap {
        val deg = when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
    }
}
