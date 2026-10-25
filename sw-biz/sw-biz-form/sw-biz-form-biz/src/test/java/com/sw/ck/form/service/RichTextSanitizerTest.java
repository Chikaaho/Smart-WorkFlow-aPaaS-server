package com.sw.ck.form.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RichTextSanitizerTest {

    @Test
    void legacyPlainTextIsKeptAsTextAndNotParsedAsHtml() {
        assertEquals("<b>旧文本</b>", RichTextSanitizer.sanitize("<b>旧文本</b>"));
        assertEquals("<b>旧文本</b>", RichTextSanitizer.visibleText("<b>旧文本</b>"));
        assertEquals("<script>alert(1)</script>",
                RichTextSanitizer.visibleText("<script>alert(1)</script>"));
    }

    @Test
    void sanitizeRemovesScriptEventStyleLinksAndExternalContent() {
        String dirty = RichTextSanitizer.PREFIX
                + "<p onclick=\"alert(1)\">Hi<script>alert(1)</script>"
                + "<span data-rt-font=\"song\" data-rt-size=\"16\" data-rt-color=\"#6f2dff\""
                + " style=\"background:url(x)\">A</span>"
                + "<img src=x onerror=alert(1)><a href=\"javascript:alert(1)\">L</a></p>";
        String clean = RichTextSanitizer.sanitize(dirty);
        assertTrue(clean.startsWith(RichTextSanitizer.PREFIX));
        assertTrue(clean.contains("Hi"));
        assertTrue(clean.contains("A"));
        assertTrue(clean.contains("L"));
        assertFalse(clean.contains("script"));
        assertFalse(clean.contains("onclick"));
        assertFalse(clean.contains("onerror"));
        assertFalse(clean.contains("javascript:"));
        assertFalse(clean.contains("style="));
        assertFalse(clean.contains("#6f2dff"));
        assertTrue(clean.contains("data-rt-color=\"#6F2DFF\""));
    }

    @Test
    void invalidFontSizeColorTokensAreDropped() {
        String clean = RichTextSanitizer.sanitize(RichTextSanitizer.PREFIX
                + "<span data-rt-font=\"comic\" data-rt-size=\"99\" data-rt-color=\"red\">X</span>"
                + "<span data-rt-size=\"14\"></span>");
        assertEquals(RichTextSanitizer.PREFIX + "X", clean);
    }

    @Test
    void visibleTextIgnoresMarkupAndCountsBlockBreaks() {
        String value = RichTextSanitizer.PREFIX + "<p>你好</p><p>世界</p>";
        assertEquals("你好\n世界", RichTextSanitizer.visibleText(value));
        assertEquals("你好", RichTextSanitizer.visibleText(RichTextSanitizer.PREFIX + "<p>你好</p>"));
        assertEquals("第一行\n第二行",
                RichTextSanitizer.visibleText(RichTextSanitizer.PREFIX + "第一行<br>第二行"));
    }

    @Test
    void formattingOnlyValueNormalizesToEmpty() {
        assertEquals("", RichTextSanitizer.sanitize(RichTextSanitizer.PREFIX + "<p><br></p>"));
        assertEquals("", RichTextSanitizer.sanitize(RichTextSanitizer.PREFIX
                + "<p><span data-rt-size=\"14\"> </span></p>"));
    }

    @Test
    void nullAndEmptyAreSafe() {
        assertNull(RichTextSanitizer.sanitize(null));
        assertEquals("", RichTextSanitizer.visibleText(null));
        assertEquals("", RichTextSanitizer.sanitize(""));
    }
}
