package com.bridge.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OCR 识别服务
 * 使用 ML Kit 中文文本识别
 */
object OcrService {

    private const val TAG = "OcrService"

    // 中文文本识别器
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * 识别结果
     */
    data class OcrResult(
        val success: Boolean,
        val textBlocks: List<TextBlock> = emptyList(),
        val fullText: String = "",
        val error: String? = null,
        val processingTimeMs: Long = 0
    )

    /**
     * 文本块
     */
    data class TextBlock(
        val text: String,
        val bounds: Rect,
        val confidence: Float = 1.0f,
        val lines: List<TextLine> = emptyList()
    )

    /**
     * 文本行
     */
    data class TextLine(
        val text: String,
        val bounds: Rect,
        val elements: List<TextElement> = emptyList()
    )

    /**
     * 文本元素（最小单位）
     */
    data class TextElement(
        val text: String,
        val bounds: Rect,
        val confidence: Float = 1.0f
    )

    /**
     * 识别图片中的文字
     * @param bitmap 要识别的图片
     * @return 识别结果
     */
    suspend fun recognize(bitmap: Bitmap): OcrResult {
        return suspendCancellableCoroutine { continuation ->
            val startTime = System.currentTimeMillis()

            try {
                val inputImage = InputImage.fromBitmap(bitmap, 0)

                recognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        val processingTime = System.currentTimeMillis() - startTime

                        val textBlocks = mutableListOf<TextBlock>()
                        val fullTextBuilder = StringBuilder()

                        for (block in visionText.textBlocks) {
                            val lines = mutableListOf<TextLine>()

                            for (line in block.lines) {
                                val elements = mutableListOf<TextElement>()

                                for (element in line.elements) {
                                    elements.add(TextElement(
                                        text = element.text,
                                        bounds = element.boundingBox ?: Rect(),
                                        confidence = element.confidence ?: 1.0f
                                    ))
                                }

                                lines.add(TextLine(
                                    text = line.text,
                                    bounds = line.boundingBox ?: Rect(),
                                    elements = elements
                                ))

                                fullTextBuilder.append(line.text).append("\n")
                            }

                            textBlocks.add(TextBlock(
                                text = block.text,
                                bounds = block.boundingBox ?: Rect(),
                                lines = lines
                            ))
                        }

                        val result = OcrResult(
                            success = true,
                            textBlocks = textBlocks,
                            fullText = fullTextBuilder.toString().trim(),
                            processingTimeMs = processingTime
                        )

                        Log.d(TAG, "OCR success: ${textBlocks.size} blocks, ${processingTime}ms")
                        continuation.resume(result)
                    }
                    .addOnFailureListener { e ->
                        val processingTime = System.currentTimeMillis() - startTime
                        Log.e(TAG, "OCR failed", e)

                        continuation.resume(OcrResult(
                            success = false,
                            error = e.message ?: "OCR failed",
                            processingTimeMs = processingTime
                        ))
                    }
            } catch (e: Exception) {
                val processingTime = System.currentTimeMillis() - startTime
                Log.e(TAG, "OCR exception", e)

                continuation.resume(OcrResult(
                    success = false,
                    error = e.message ?: "OCR exception",
                    processingTimeMs = processingTime
                ))
            }
        }
    }

    /**
     * 检查模型是否已下载
     */
    fun isModelDownloaded(context: Context): Boolean {
        // ML Kit 会自动下载模型，这里简单返回 true
        // 实际可以使用 RemoteModelManager 检查
        return true
    }

    /**
     * 下载模型（如果需要）
     */
    fun downloadModelIfNeeded(context: Context, onComplete: (Boolean) -> Unit) {
        // ML Kit 中文模型会在首次使用时自动下载
        // 这里直接回调成功
        onComplete(true)
    }
}
