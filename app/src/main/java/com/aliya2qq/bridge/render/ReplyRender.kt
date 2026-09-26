package com.aliya2qq.bridge.render

import com.aliya2qq.bridge.misskey.MisskeyClient
import java.util.regex.Pattern

/** 一条出站消息中的内容块：text 或 image。 */
data class ContentBlock(val kind: String, val value: String) {
    val isImage get() = kind == "image"
}

data class RenderedReply(
    val segments: List<String> = emptyList(),
    val images: List<String> = emptyList(),
    val heartRates: List<Pair<Int, Int>> = emptyList(),
    val messages: List<List<ContentBlock>> = emptyList(),
)

/**
 * AI 回复渲染管线，代替 Misskey 前端完成消息后处理：
 * 1. [[agent_draw size=… tag=…]] 画图指令
 * 2. [[heart_rate:min,max]] 心率指令
 * 3. 自定义表情 :name:
 * 4. 分段输出（segmentedOutputEnabled）
 */
object ReplyRender {
    private val AGENT_DRAW = Pattern.compile("""\[\[agent_draw\s+size=([^\s]+)\s+tag=([^\]]+)\]\]""")
    private val HEART_RATE = Pattern.compile("""\[\[heart_rate:\s*(\d+)\s*,\s*(\d+)\s*\]\]""")
    private val EMOJI_CODE = Pattern.compile("""(?<![\w:]):([A-Za-z0-9_+.\-]{1,100}):(?![\w:])""")
    private val MD_SEPARATOR = Pattern.compile("""^\s{0,3}(?:(?:-{3,})|(?:_{3,})|(?:\*{3,}))\s*$""")
    private val TABLE_DELIMITER = Pattern.compile("""^\s*\|?(?:\s*:?-{3,}:?\s*\|)+\s*:?-{3,}:?\s*\|?\s*$""")
    private val LIST_ITEM = Pattern.compile("""^\s*(?:[-+*]|\d+[.)])\s+""")
    private val LIST_CONTINUATION = Pattern.compile("""^\s{2,}\S""")
    private val QUOTE = Pattern.compile("""^\s*>""")
    private val FENCE_START = Pattern.compile("""^\s*(`{3,}|~{3,})""")
    private val HTML_TAG = Pattern.compile("""<!--.*?-->|</?([A-Za-z][\w:-]*)(?:\s[^<>]*?)?/?>""", Pattern.DOTALL)
    private val HTML_SELF_CLOSING = Pattern.compile("""/\s*>$""")

    private val HTML_VOID_TAGS = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr",
    )

    data class StripResult(val clean: String, val drawCount: Int, val heartRates: List<Pair<Int, Int>>)

    fun stripInstructions(text: String): StripResult {
        val source = text
        val dm = AGENT_DRAW.matcher(source)
        var drawCount = 0
        while (dm.find()) drawCount++
        var clean = AGENT_DRAW.matcher(source).replaceAll("")
        val hr = HEART_RATE.matcher(clean)
        val rates = ArrayList<Pair<Int, Int>>()
        while (hr.find()) {
            rates.add(hr.group(1)!!.toInt() to hr.group(2)!!.toInt())
        }
        clean = HEART_RATE.matcher(clean).replaceAll("")
        return StripResult(clean.trim(), drawCount, rates)
    }

    fun stripEmojiCodes(text: String): String =
        EMOJI_CODE.matcher(text).replaceAll("").trim()

    data class Part(val kind: String, val value: String)

    fun splitEmojiCodes(text: String): List<Part> {
        val raw = text
        val parts = ArrayList<Part>()
        var last = 0
        val m = EMOJI_CODE.matcher(raw)
        while (m.find()) {
            if (m.start() > last) parts.add(Part("text", raw.substring(last, m.start())))
            parts.add(Part("emoji", m.group(1)!!))
            last = m.end()
        }
        if (last < raw.length) parts.add(Part("text", raw.substring(last)))
        return parts
    }

    fun expandEmojisStatic(blocks: MutableList<ContentBlock>, text: String, resolve: (String) -> String?) {
        for ((kind, value) in splitEmojiCodes(text)) {
            if (kind == "text") {
                if (value.isNotEmpty()) blocks.add(ContentBlock("text", value))
                continue
            }
            val url = resolve(value)
            if (url != null) blocks.add(ContentBlock("image", url))
        }
    }

    suspend fun expandEmojis(misskey: MisskeyClient, text: String): List<ContentBlock> {
        val blocks = ArrayList<ContentBlock>()
        for ((kind, value) in splitEmojiCodes(text)) {
            if (kind == "text") {
                if (value.isNotEmpty()) blocks.add(ContentBlock("text", value))
                continue
            }
            val url = misskey.emojiUrl(value)
            if (url != null) blocks.add(ContentBlock("image", url))
        }
        return blocks
    }

    suspend fun renderReply(
        misskey: MisskeyClient,
        token: String,
        sessionId: String,
        text: String,
        messageId: String?,
        splitEnabled: Boolean = true,
    ): RenderedReply {
        val (clean, drawCount, rates) = stripInstructions(text)
        val images = ArrayList<String>()
        if (messageId != null && drawCount > 0) {
            for (index in 0 until drawCount) {
                val url = misskey.generatePlaceholder(token, sessionId, messageId, index)
                if (url != null) images.add(url)
            }
        }

        if (clean.isEmpty()) {
            return RenderedReply(images = images, heartRates = rates)
        }

        val emojiUrls = ArrayList<String>()
        val parts = StringBuilder()
        for ((kind, value) in splitEmojiCodes(clean)) {
            if (kind == "text") {
                parts.append(value)
                continue
            }
            val url = misskey.emojiUrl(value)
            if (url != null) {
                emojiUrls.add(url)
                parts.append("\u0001${emojiUrls.size - 1}\u0001")
            }
        }
        val marked = parts.toString()

        val rawSegments = if (splitEnabled) {
            if (marked.trim().isNotEmpty()) splitIntoSegments(marked) else emptyList()
        } else {
            if (marked.trim().isNotEmpty()) listOf(marked) else emptyList()
        }

        val phRe = Pattern.compile("\u0001(\\d+)\u0001")
        val messages = ArrayList<List<ContentBlock>>()
        val plainSegments = ArrayList<String>()
        for (seg in rawSegments) {
            val blocks = ArrayList<ContentBlock>()
            val textAcc = StringBuilder()
            var last = 0
            val m = phRe.matcher(seg)
            while (m.find()) {
                val piece = seg.substring(last, m.start())
                if (piece.isNotEmpty()) {
                    blocks.add(ContentBlock("text", piece))
                    textAcc.append(piece)
                }
                val idx = m.group(1)!!.toInt()
                if (idx in emojiUrls.indices) {
                    blocks.add(ContentBlock("image", emojiUrls[idx]))
                }
                last = m.end()
            }
            val tail = seg.substring(last)
            if (tail.isNotEmpty()) {
                blocks.add(ContentBlock("text", tail))
                textAcc.append(tail)
            }
            val plain = textAcc.toString().trim()
            if (blocks.isEmpty()) continue
            plainSegments.add(plain)
            messages.add(blocks)
        }

        return RenderedReply(
            segments = plainSegments,
            images = images,
            heartRates = rates,
            messages = messages,
        )
    }

    /** 按 Markdown 结构（围栏、表格、列表、引用、HTML 块）把整段回复拆成多条消息。 */
    fun splitIntoSegments(source: String?): List<String> {
        if (source == null) return emptyList()
        val original = source
        val lines = original.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val segments = ArrayList<String>()

        fun push(ls: List<String>) {
            val text = ls.joinToString("\n").trim()
            if (text.isNotEmpty()) segments.add(text)
        }

        var i = 0
        val n = lines.size
        while (i < n) {
            val line = lines[i]
            if (line.isBlank() || MD_SEPARATOR.matcher(line).matches()) {
                i++
                continue
            }

            val fence = FENCE_START.matcher(line)
            if (fence.find()) {
                val mark = fence.group(1)!!
                val char = mark[0]
                val endPattern = Pattern.compile("^\\s*" + Pattern.quote(char.toString()) + "{" + mark.length + ",}\\s*$")
                val block = ArrayList<String>()
                block.add(line)
                i++
                while (i < n) {
                    block.add(lines[i])
                    if (endPattern.matcher(lines[i]).matches()) {
                        i++
                        break
                    }
                    i++
                }
                push(block)
                continue
            }

            if ("|" in line && i + 1 < n && TABLE_DELIMITER.matcher(lines[i + 1]).matches()) {
                val table = ArrayList<String>()
                table.add(line)
                table.add(lines[i + 1])
                i += 2
                while (i < n && lines[i].isNotBlank() && "|" in lines[i]) {
                    table.add(lines[i])
                    i++
                }
                push(table)
                continue
            }

            if (LIST_ITEM.matcher(line).find()) {
                val block = ArrayList<String>()
                block.add(line)
                i++
                while (i < n && (LIST_ITEM.matcher(lines[i]).find() || LIST_CONTINUATION.matcher(lines[i]).find())) {
                    block.add(lines[i])
                    i++
                }
                push(block)
                continue
            }

            if (QUOTE.matcher(line).find()) {
                val block = ArrayList<String>()
                block.add(line)
                i++
                while (i < n && QUOTE.matcher(lines[i]).find()) {
                    block.add(lines[i])
                    i++
                }
                push(block)
                continue
            }

            if (line.contains("[[agent_draw")) {
                val block = ArrayList<String>()
                block.add(line)
                i++
                while (!block.joinToString("\n").contains("]]") && i < n) {
                    block.add(lines[i])
                    i++
                }
                push(block)
                continue
            }

            val stack = ArrayList<String>()
            if (updateHtmlStack(line, stack)) {
                val block = ArrayList<String>()
                block.add(line)
                i++
                while (stack.isNotEmpty() && i < n) {
                    block.add(lines[i])
                    updateHtmlStack(lines[i], stack)
                    i++
                }
                push(block)
                continue
            }

            push(listOf(line))
            i++
        }

        if (segments.isEmpty() && original.trim().isNotEmpty()) {
            return listOf(original.trim())
        }
        return segments
    }

    fun updateHtmlStack(line: String, stack: MutableList<String>): Boolean {
        var sawHtml = false
        val m = HTML_TAG.matcher(line)
        while (m.find()) {
            sawHtml = true
            val tag = m.group(1) ?: continue
            val raw = m.group(0)
            val low = tag.lowercase()
            if (low in HTML_VOID_TAGS || HTML_SELF_CLOSING.matcher(raw).find()) continue
            if (raw.startsWith("</")) {
                for (idx in stack.indices.reversed()) {
                    if (stack[idx] == low) {
                        while (stack.size > idx) stack.removeAt(stack.size - 1)
                        break
                    }
                }
            } else {
                stack.add(low)
            }
        }
        return sawHtml
    }

    /** 按可见字符数计算分步发送间隔（1~3 秒）。 */
    fun segmentDelayMs(segment: String): Int {
        val visible = segment.replace(Regex("<[^>]*>|\\s+"), "").length
        return maxOf(1000, minOf(3000, 1000 + visible * 20))
    }
}
