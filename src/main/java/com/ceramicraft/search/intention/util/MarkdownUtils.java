package com.ceramicraft.search.intention.util;

/**
 * Markdown 清理工具。
 */
public final class MarkdownUtils {

    private MarkdownUtils() {}

    /**
     * 去掉 LLM 返回的 markdown code block 标记（{@code ```json ... ```}）。
     */
    public static String cleanMarkdown(String raw) {
        String s = raw.strip();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) s = s.substring(nl + 1);
        }
        if (s.endsWith("```")) {
            s = s.substring(0, s.length() - 3);
        }
        return s.strip();
    }
}

