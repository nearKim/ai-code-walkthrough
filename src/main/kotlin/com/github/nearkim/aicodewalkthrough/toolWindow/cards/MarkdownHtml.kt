package com.github.nearkim.aicodewalkthrough.toolwindow.cards

/**
 * The small Markdown subset the tool window renders: fenced code, bullet and numbered lists,
 * paragraphs, and inline code/bold/italic. Numbered items render as `<ul>` because Swing's
 * HTMLEditorKit numbers `<ol>` inconsistently at this font size.
 */
internal object MarkdownHtml {

    fun document(bodyHtml: String): String = "<html><body>$bodyHtml</body></html>"

    fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun toHtml(markdown: String): String {
        if (markdown.isBlank()) return ""
        val lines = markdown.lines()
        val sb = StringBuilder()
        var inCodeBlock = false
        var inList = false
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.trimStart().startsWith("```")) {
                if (inCodeBlock) {
                    sb.append("</pre>")
                    inCodeBlock = false
                } else {
                    if (inList) { sb.append("</ul>"); inList = false }
                    sb.append("<pre>")
                    inCodeBlock = true
                }
                i++
                continue
            }
            if (inCodeBlock) {
                sb.append(escape(line)).append("\n")
                i++
                continue
            }
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                if (inList) { sb.append("</ul>"); inList = false }
                i++
                continue
            }
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                if (!inList) { sb.append("<ul>"); inList = true }
                sb.append("<li>").append(inlineFormat(escape(trimmed.substring(2)))).append("</li>")
                i++
                continue
            }
            val numberedMatch = NUMBERED_ITEM.find(trimmed)
            if (numberedMatch != null) {
                if (!inList) { sb.append("<ul>"); inList = true }
                sb.append("<li>").append(inlineFormat(escape(numberedMatch.groupValues[2]))).append("</li>")
                i++
                continue
            }
            if (inList) { sb.append("</ul>"); inList = false }
            sb.append("<p>").append(inlineFormat(escape(trimmed))).append("</p>")
            i++
        }
        if (inCodeBlock) sb.append("</pre>")
        if (inList) sb.append("</ul>")
        return sb.toString()
    }

    private fun inlineFormat(escaped: String): String {
        var result = escaped
        result = INLINE_CODE.replace(result) { "<code>${it.groupValues[1]}</code>" }
        result = BOLD.replace(result) { "<b>${it.groupValues[1]}</b>" }
        result = ITALIC.replace(result) { "<i>${it.groupValues[1]}</i>" }
        return result
    }

    private val NUMBERED_ITEM = Regex("^(\\d+)\\.\\s+(.*)")
    private val INLINE_CODE = Regex("`([^`]+)`")
    private val BOLD = Regex("\\*\\*([^*]+)\\*\\*")
    private val ITALIC = Regex("\\*([^*]+)\\*")
}
