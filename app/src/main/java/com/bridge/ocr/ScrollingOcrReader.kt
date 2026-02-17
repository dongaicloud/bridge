package com.bridge.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.bridge.BridgeAccessibilityService
import com.bridge.model.ContactData
import com.bridge.model.ReadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 滚动 OCR 读取器
 * 用于滚动屏幕并读取完整列表
 */
class ScrollingOcrReader(
    private val context: Context,
    private val screenshotHelper: ScreenshotHelper
) {
    companion object {
        private const val TAG = "ScrollingOcrReader"
        private const val SCROLL_RATIO = 0.7f  // 滚动屏幕高度的70%
        private const val SCROLL_DURATION = 300L  // 滚动持续时间(ms)
        private const val STABILIZE_DELAY = 800L  // 等待屏幕稳定时间(ms)
        private const val MAX_CONSECUTIVE_SAME = 3  // 连续3次相同则停止
    }

    // 联系人黑名单（需要过滤的功能入口）
    private val contactBlacklist = setOf(
        "新的朋友", "仅聊天的朋友", "群聊", "标签", "公众号",
        "搜一搜", "附近的", "通讯录", "搜索", "添加",
        "设置", "更多", "取消", "确定", "微信"
    )

    /**
     * 滚动读取联系人列表
     */
    suspend fun readContactsScrolling(service: BridgeAccessibilityService): ReadResult = withContext(Dispatchers.IO) {
        return@withContext withTimeoutOrNull(120_000L) {  // 最多2分钟
            try {
                val allContacts = mutableListOf<ContactData>()
                val seenNames = mutableSetOf<String>()
                var consecutiveSameCount = 0
                var lastScreenText = ""
                var scrollCount = 0

                Log.d(TAG, "开始滚动读取联系人列表")

                while (consecutiveSameCount < MAX_CONSECUTIVE_SAME) {
                    // 1. 截图
                    val screenshot = screenshotHelper.capture()
                    if (!screenshot.success || screenshot.bitmap == null) {
                        Log.e(TAG, "截图失败: ${screenshot.error}")
                        break
                    }

                    // 2. OCR识别
                    val ocrResult = OcrService.recognize(screenshot.bitmap!!)
                    if (!ocrResult.success) {
                        Log.e(TAG, "OCR识别失败: ${ocrResult.error}")
                        break
                    }

                    // 3. 检测是否滚动到底部（连续相同内容）
                    val currentScreenText = ocrResult.fullText
                    if (currentScreenText == lastScreenText) {
                        consecutiveSameCount++
                        Log.d(TAG, "屏幕内容相同 ($consecutiveSameCount/$MAX_CONSECUTIVE_SAME)")
                    } else {
                        consecutiveSameCount = 0
                    }
                    lastScreenText = currentScreenText

                    // 4. 提取联系人
                    val screenContacts = extractContacts(ocrResult.textBlocks, screenshot.bitmap!!)
                    val newContacts = screenContacts.filter { it.name !in seenNames }

                    newContacts.forEach { contact ->
                        seenNames.add(contact.name)
                        allContacts.add(contact)
                        Log.d(TAG, "发现联系人: ${contact.name}")
                    }

                    Log.d(TAG, "本次屏幕识别: ${screenContacts.size} 个, 新增: ${newContacts.size} 个, 累计: ${allContacts.size} 个")

                    // 5. 如果连续相同次数达到阈值，停止滚动
                    if (consecutiveSameCount >= MAX_CONSECUTIVE_SAME) {
                        Log.d(TAG, "连续 $MAX_CONSECUTIVE_SAME 次内容相同，停止滚动")
                        break
                    }

                    // 6. 滚动屏幕
                    val screenBounds = service.getScreenBounds()
                    val centerX = screenBounds.width() / 2
                    val startY = (screenBounds.height() * 0.7).toInt()
                    val endY = (screenBounds.height() * (1 - SCROLL_RATIO)).toInt()

                    service.swipe(centerX, startY, centerX, endY, SCROLL_DURATION)
                    scrollCount++

                    Log.d(TAG, "滚动 #$scrollCount: ($centerX, $startY) -> ($centerX, $endY)")

                    // 7. 等待屏幕稳定
                    Thread.sleep(STABILIZE_DELAY)
                }

                Log.d(TAG, "滚动读取完成: 共 ${allContacts.size} 个联系人, 滚动 $scrollCount 次")

                if (allContacts.isEmpty()) {
                    ReadResult.error("未识别到联系人")
                } else {
                    ReadResult.successContacts(allContacts)
                }
            } catch (e: Exception) {
                Log.e(TAG, "滚动读取失败", e)
                ReadResult.error("读取失败: ${e.message}")
            }
        } ?: ReadResult.error("读取超时")
    }

    /**
     * 从 OCR 结果中提取联系人
     */
    private fun extractContacts(
        textBlocks: List<OcrService.TextBlock>,
        bitmap: Bitmap
    ): List<ContactData> {
        val contacts = mutableListOf<ContactData>()
        val screenHeight = bitmap.height
        val screenWidth = bitmap.width

        for (block in textBlocks) {
            val text = block.text.trim()

            // 过滤条件
            if (!isValidContactName(text, block.bounds, screenWidth, screenHeight)) {
                continue
            }

            contacts.add(ContactData(
                name = text,
                displayName = text,
                lastMessage = null,
                lastTime = null,
                unreadCount = 0
            ))
        }

        return contacts
    }

    /**
     * 判断是否是有效的联系人名称
     */
    private fun isValidContactName(text: String, bounds: Rect, screenWidth: Int, screenHeight: Int): Boolean {
        // 1. 长度检查（2-20字符，支持中文、英文、表情）
        if (text.length < 2 || text.length > 20) {
            return false
        }

        // 2. 黑名单检查
        if (text in contactBlacklist) {
            return false
        }

        // 3. 纯数字检查（排除电话号码等）
        if (text.matches(Regex("^\\d+$"))) {
            return false
        }

        // 4. 时间格式检查
        if (text.matches(Regex("^\\d{1,2}:\\d{2}$"))) {
            return false
        }
        if (text.matches(Regex("^昨天.*\\d{1,2}:\\d{2}$"))) {
            return false
        }
        if (text.matches(Regex("^\\d{4}年\\d{1,2}月\\d{1,2}日.*"))) {
            return false
        }

        // 5. 位置检查（联系人名称通常在屏幕上半部分，排除底部导航）
        if (bounds.top > screenHeight * 0.85) {
            return false
        }

        // 6. 高度检查（排除过大的标题等）
        if (bounds.height() > screenHeight * 0.1) {
            return false
        }

        // 7. 必须包含至少一个字母或中文（支持表情符号昵称）
        val hasLetterOrChinese = text.any { it.isLetter() }
        if (!hasLetterOrChinese) {
            // 纯表情符号昵称也允许
            val hasEmoji = text.any { it.code > 0x1F000 }
            if (!hasEmoji) {
                return false
            }
        }

        return true
    }
}
