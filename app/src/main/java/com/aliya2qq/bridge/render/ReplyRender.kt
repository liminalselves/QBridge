package com.aliya2qq.bridge.render

import java.util.regex.Matcher
import java.util.regex.Pattern

/** 一条出站消息中的内容块：text 或 image。 */
data class ContentBlock(val kind: String, val value: String) {
    val isImage get() = kind == "image"
}

/**
 * @param messages 依次发送的 QQ 消息，每条由文字/图片块混排
 * @param drawCount 有效画图占位符个数，序号与服务端 generate-placeholder 的 placeholderIndex 一致
 */
data class RenderedReply(
    val messages: List<List<ContentBlock>> = emptyList(),
    val drawCount: Int = 0,
)

/**
 * AI 回复渲染管线，代替 Misskey 前端完成消息后处理：
 * 1. [[agent_draw size=… tag=…]] 画图占位符：统计后从正文移除，由调用方另行生图
 * 2. [[agent_sticker key=…]] 角色表情包、:name: 全站表情 → 图片
 * 3. 其余 [[agent_*]] / [[wb:*]] 机器指令移除
 * 4. 分段输出（segmentedOutputEnabled）：按 Markdown 结构拆成多条
 */
object ReplyRender {
    // 与服务端 AgentImageService.AGENT_DRAW_RE 一致：size 可省略，tag 为空的不计入序号
    private val AGENT_DRAW = Pattern.compile("""\[\[agent_draw(?:\s+size=(portrait|landscape|square))?\s+tag=([\s\S]*?)\]\]""")
    private val AGENT_DRAW_ANY = Pattern.compile("""\[\[agent_draw\b[\s\S]*?\]\]""", Pattern.CASE_INSENSITIVE)
    private val STICKER = Pattern.compile("""\[\[agent_sticker\s+key=([a-zA-Z0-9_-]{1,32})\s*\]\]""")
    private val OTHER_DIRECTIVE = Pattern.compile("""\[\[(?:agent_[a-z_]+|wb:|heart_rate:)[^\]]*\]\]""", Pattern.CASE_INSENSITIVE)
    private val EMOJI_CODE = Pattern.compile("""(?<![\w:]):([a-zA-Z0-9_]{1,100}):(?![\w:])""")
    private val MEDIA_TOKEN = Pattern.compile("""\u0001(\d+)\u0001""")

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

    /** 有效画图占位符个数（与服务端 parseDrawPlaceholders 计数规则一致）。 */
    fun countDraws(text: String): Int {
        val m = AGENT_DRAW.matcher(text)
        var n = 0
        while (m.find()) if (!m.group(2).isNullOrBlank()) n++
        return n
    }

    /** 去掉所有机器指令，得到适合预览/历史展示的纯文本（表情短代码保留）。 */
    fun plainText(text: String): String {
        var t = AGENT_DRAW_ANY.matcher(text).replaceAll("[画图]")
        t = STICKER.matcher(t).replaceAll("[表情]")
        t = OTHER_DIRECTIVE.matcher(t).replaceAll("")
        return t.trim()
    }

    /**
     * @param resolveEmoji 全站表情名 → 图片地址；null 表示实例没有该表情，保留原文
     * @param resolveSticker 角色表情包 key → 图片地址；null 时整体移除该标签
     */
    fun render(
        text: String,
        splitEnabled: Boolean,
        resolveEmoji: (String) -> String?,
        resolveSticker: (String) -> String?,
    ): RenderedReply {
        val drawCount = countDraws(text)
        var clean = AGENT_DRAW_ANY.matcher(text).replaceAll("")

        // 表情包/表情先换成占位记号，分段时作为普通字符参与，最后再还原成图片块
        val media = ArrayList<String>()
        fun token(url: String): String {
            media.add(url)
            return "\u0001${media.size - 1}\u0001"
        }
        clean = replaceAll(STICKER, clean) { m -> resolveSticker(m.group(1)!!)?.let(::token) ?: "" }
        clean = OTHER_DIRECTIVE.matcher(clean).replaceAll("")
        clean = replaceAll(EMOJI_CODE, clean) { m -> resolveEmoji(m.group(1)!!)?.let(::token) ?: m.group(0)!! }
        clean = clean.trim()
        if (clean.isEmpty()) return RenderedReply(drawCount = drawCount)

        val rawSegments = if (splitEnabled) splitIntoSegments(clean) else listOf(clean)
        val messages = rawSegments.mapNotNull { seg -> toBlocks(seg, media).ifEmpty { null } }
        return RenderedReply(messages = messages, drawCount = drawCount)
    }

    private fun replaceAll(p: Pattern, input: String, fn: (Matcher) -> String): String {
        val m = p.matcher(input)
        val sb = StringBuffer()
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(fn(m)))
        m.appendTail(sb)
        return sb.toString()
    }

    private fun toBlocks(seg: String, media: List<String>): List<ContentBlock> {
        val raw = ArrayList<ContentBlock>()
        var last = 0
        val m = MEDIA_TOKEN.matcher(seg)
        while (m.find()) {
            val piece = seg.substring(last, m.start())
            if (piece.isNotBlank()) raw.add(ContentBlock("text", piece))
            media.getOrNull(m.group(1)!!.toInt())?.let { raw.add(ContentBlock("image", it)) }
            last = m.end()
        }
        val tail = seg.substring(last)
        if (tail.isNotBlank()) raw.add(ContentBlock("text", tail))
        // 紧贴图片的换行/空格在 QQ 里会显示成空行，裁掉
        return raw.mapIndexed { i, b ->
            if (b.isImage) return@mapIndexed b
            var v = b.value
            if (i == 0 || raw[i - 1].isImage) v = v.trimStart()
            if (i == raw.size - 1 || raw[i + 1].isImage) v = v.trimEnd()
            b.copy(value = v)
        }.filter { it.isImage || it.value.isNotEmpty() }
    }

    /** 按 Markdown 结构（围栏、表格、列表、引用、HTML 块）把整段回复拆成多条消息。 */
    fun splitIntoSegments(source: String): List<String> {
        val lines = source.replace("\r\n", "\n").replace("\r", "\n").split("\n")
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
                val endPattern = Pattern.compile("^\\s*" + Pattern.quote(mark[0].toString()) + "{" + mark.length + ",}\\s*$")
                val block = arrayListOf(line)
                i++
                while (i < n) {
                    block.add(lines[i])
                    i++
                    if (endPattern.matcher(lines[i - 1]).matches()) break
                }
                push(block)
                continue
            }

            if ("|" in line && i + 1 < n && TABLE_DELIMITER.matcher(lines[i + 1]).matches()) {
                val table = arrayListOf(line, lines[i + 1])
                i += 2
                while (i < n && lines[i].isNotBlank() && "|" in lines[i]) {
                    table.add(lines[i])
                    i++
                }
                push(table)
                continue
            }

            if (LIST_ITEM.matcher(line).find()) {
                val block = arrayListOf(line)
                i++
                while (i < n && (LIST_ITEM.matcher(lines[i]).find() || LIST_CONTINUATION.matcher(lines[i]).find())) {
                    block.add(lines[i])
                    i++
                }
                push(block)
                continue
            }

            if (QUOTE.matcher(line).find()) {
                val block = arrayListOf(line)
                i++
                while (i < n && QUOTE.matcher(lines[i]).find()) {
                    block.add(lines[i])
                    i++
                }
                push(block)
                continue
            }

            val stack = ArrayList<String>()
            if (updateHtmlStack(line, stack)) {
                val block = arrayListOf(line)
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

        if (segments.isEmpty() && source.isNotBlank()) return listOf(source.trim())
        return segments
    }

    private fun updateHtmlStack(line: String, stack: MutableList<String>): Boolean {
        var sawHtml = false
        val m = HTML_TAG.matcher(line)
        while (m.find()) {
            sawHtml = true
            val tag = m.group(1) ?: continue
            val raw = m.group(0)!!
            val low = tag.lowercase()
            if (low in HTML_VOID_TAGS || HTML_SELF_CLOSING.matcher(raw).find()) continue
            if (raw.startsWith("</")) {
                val idx = stack.lastIndexOf(low)
                if (idx >= 0) while (stack.size > idx) stack.removeAt(stack.size - 1)
            } else {
                stack.add(low)
            }
        }
        return sawHtml
    }

    /** 按可见字符数计算分步发送间隔（1~3 秒），模拟打字节奏。 */
    fun segmentDelayMs(segment: String): Long {
        val visible = segment.replace(Regex("<[^>]*>|\\s+"), "").length
        return (1000L + visible * 20L).coerceIn(1000L, 3000L)
    }
}
