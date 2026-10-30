package com.sw.ck.form.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * RICH_TEXT 服务端权威清洗（direction-ui-semantics-020）。
 *
 * <p>编码契约：新值 = {@value #PREFIX} + 受控 HTML；旧值无前缀 → 纯文本原样保留。
 * 受控 HTML 只允许 p/div/br/span/b/strong/i/em/u/s/ul/ol/li；span 只允许
 * data-rt-font / data-rt-size / data-rt-color 三个令牌，字体/字号/颜色按固定集合校验；
 * p/div 另允许对齐令牌 data-rt-align(left|center|right)与缩进令牌 data-rt-indent(0|1|2)，
 * 默认值 left/0 不落库；del/strike 归一为受控 s 标签。
 * 脚本、样式、事件属性、链接、图片和外部内容一律移除或不展开。
 * 服务端是保存内容的权威，前端清洗不能替代本类。</p>
 */
public final class RichTextSanitizer {

    public static final String PREFIX = "<!--sw-rt:1-->";

    private static final Set<String> ALLOWED_TAGS = Set.of(
            "p", "div", "br", "span", "b", "strong", "i", "em", "u", "s", "ul", "ol", "li");
    private static final Set<String> STRIKE_TAGS = Set.of("del", "strike");
    private static final Set<String> FONT_TOKENS = Set.of(
            "default", "song", "hei", "yahei", "arial", "times");
    private static final Set<String> SIZE_TOKENS = Set.of(
            "12", "14", "16", "18", "20", "24", "28");
    private static final Pattern COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    private static final Set<String> ALIGN_TOKENS = Set.of("left", "center", "right");
    private static final Set<String> INDENT_TOKENS = Set.of("0", "1", "2");
    private static final Set<String> DROP_TAGS = Set.of(
            "script", "style", "iframe", "object", "embed", "img", "video", "audio",
            "svg", "math", "link", "meta", "form", "input", "button", "textarea",
            "select", "option");
    private static final Set<String> BLOCK_TAGS = Set.of("p", "div", "li");

    private RichTextSanitizer() {
    }

    public static boolean isRichText(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /**
     * 纯文本值原样返回；带前缀值清洗后重新加前缀。
     * 仅格式无正文时归一为空字符串，供必填/长度按可见文本判定。
     */
    public static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        if (!isRichText(value)) {
            return value;
        }
        Document document = Jsoup.parseBodyFragment(value.substring(PREFIX.length()));
        // 与前端清洗同口径输出紧凑 HTML，块间不留序列化换行。
        document.outputSettings(new Document.OutputSettings().prettyPrint(false));
        Element body = document.body();
        removeComments(body);
        clean(body);
        if (visibleText(body).trim().isEmpty()) {
            return "";
        }
        return PREFIX + body.html();
    }

    /** 用户可见文本：标签不计数，br/块级边界按换行计。 */
    public static String visibleText(String value) {
        if (value == null) {
            return "";
        }
        if (!isRichText(value)) {
            return value;
        }
        Document document = Jsoup.parseBodyFragment(value.substring(PREFIX.length()));
        return visibleText(document.body());
    }

    private static void clean(Element root) {
        for (Element element : new ArrayList<>(root.getAllElements())) {
            if (element == root || !element.parents().contains(root)) {
                continue;
            }
            String tag = element.tagName().toLowerCase(Locale.ROOT);
            if (DROP_TAGS.contains(tag)) {
                element.remove();
                continue;
            }
            if (STRIKE_TAGS.contains(tag)) {
                // 删除线统一受控 s 标签，历史 del/strike 来源同口径。
                element.tagName("s");
                tag = "s";
            }
            if (!ALLOWED_TAGS.contains(tag)) {
                element.unwrap();
                continue;
            }
            Map<String, String> keep = new HashMap<>();
            if ("span".equals(tag)) {
                for (Attribute attribute : element.attributes().asList()) {
                    String name = attribute.getKey();
                    String value = attribute.getValue();
                    if ("data-rt-font".equals(name) && FONT_TOKENS.contains(value)) {
                        keep.put(name, value);
                    } else if ("data-rt-size".equals(name) && SIZE_TOKENS.contains(value)) {
                        keep.put(name, value);
                    } else if ("data-rt-color".equals(name) && COLOR.matcher(value).matches()) {
                        keep.put(name, value.toUpperCase(Locale.ROOT));
                    }
                }
            } else if ("p".equals(tag) || "div".equals(tag)) {
                for (Attribute attribute : element.attributes().asList()) {
                    String name = attribute.getKey();
                    String value = attribute.getValue();
                    if ("data-rt-align".equals(name) && ALIGN_TOKENS.contains(value)
                            && !"left".equals(value)) {
                        keep.put(name, value);
                    } else if ("data-rt-indent".equals(name) && INDENT_TOKENS.contains(value)
                            && !"0".equals(value)) {
                        keep.put(name, value);
                    }
                }
            }
            element.clearAttributes();
            keep.forEach(element::attr);
            if ("span".equals(tag) && element.attributesSize() == 0) {
                element.unwrap();
            }
        }
        // 空 span 无可见内容：移除，避免格式标记堆积。
        for (Element span : new ArrayList<>(root.select("span"))) {
            if (span.childNodes().isEmpty()) {
                span.remove();
            }
        }
    }

    private static void removeComments(Node node) {
        for (Node child : new ArrayList<>(node.childNodes())) {
            if (child instanceof Comment) {
                child.remove();
            } else {
                removeComments(child);
            }
        }
    }

    private static String visibleText(Element root) {
        StringBuilder text = new StringBuilder();
        appendVisible(root, text);
        return text.toString();
    }

    private static void appendVisible(Node node, StringBuilder text) {
        for (Node child : node.childNodes()) {
            if (child instanceof TextNode textNode) {
                text.append(textNode.text());
                continue;
            }
            if (!(child instanceof Element element)) {
                continue;
            }
            String tag = element.tagName().toLowerCase(Locale.ROOT);
            if ("br".equals(tag)) {
                text.append('\n');
                continue;
            }
            boolean block = BLOCK_TAGS.contains(tag);
            // 只在块与块之间补换行，末尾不额外补；单段正文的长度即真实字数。
            if (block && text.length() > 0 && text.charAt(text.length() - 1) != '\n') {
                text.append('\n');
            }
            appendVisible(element, text);
        }
    }
}
