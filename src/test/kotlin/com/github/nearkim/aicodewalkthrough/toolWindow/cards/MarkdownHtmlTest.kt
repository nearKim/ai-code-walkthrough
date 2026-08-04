package com.github.nearkim.aicodewalkthrough.toolwindow.cards

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownHtmlTest {

    @Test
    fun `escapes markup before inline formatting is applied`() {
        assertEquals("<p>a &lt;b&gt; &amp; c</p>", MarkdownHtml.toHtml("a <b> & c"))
        assertEquals("<p><code>&lt;script&gt;</code></p>", MarkdownHtml.toHtml("`<script>`"))
    }

    @Test
    fun `formats inline code, bold and italic`() {
        assertEquals(
            "<p><code>run()</code> is <b>fast</b> and <i>safe</i></p>",
            MarkdownHtml.toHtml("`run()` is **fast** and *safe*"),
        )
    }

    @Test
    fun `groups bullet and numbered items into a single list`() {
        assertEquals("<ul><li>one</li><li>two</li></ul>", MarkdownHtml.toHtml("- one\n- two"))
        assertEquals("<ul><li>one</li><li>two</li></ul>", MarkdownHtml.toHtml("1. one\n2. two"))
    }

    @Test
    fun `closes an open list before a paragraph and a code fence`() {
        assertEquals("<ul><li>one</li></ul><p>after</p>", MarkdownHtml.toHtml("- one\nafter"))
        assertEquals("<ul><li>one</li></ul><pre>code\n</pre>", MarkdownHtml.toHtml("- one\n```\ncode\n```"))
    }

    @Test
    fun `leaves code block content unformatted and closes an unterminated fence`() {
        assertEquals("<pre>**not bold**\n</pre>", MarkdownHtml.toHtml("```\n**not bold**\n```"))
        assertEquals("<pre>x\n</pre>", MarkdownHtml.toHtml("```\nx"))
    }

    @Test
    fun `renders blank input as empty`() {
        assertEquals("", MarkdownHtml.toHtml("   "))
    }
}
