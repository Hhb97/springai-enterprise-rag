package com.example.ragassitant.infrastructure.reader;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Markdown 小节解析器：按 ATX 标题（{@code #} ~ {@code ######}）切分，并还原完整标题层级路径。
 *
 * <p><b>为什么不用 Spring AI 的 MarkdownDocumentReader</b>：实测它的 metadata 无法用于重建层级 ——
 * <ol>
 *   <li><b>没有直接正文的标题不产出 Document</b>。形如「h1 → h2 → h3(有正文)」的文档，
 *       h1/h2 都不会 emit，下游只能看到 h3，祖先层级彻底丢失；</li>
 *   <li><b>{@code category} 会被正文内容覆盖</b>。小节正文里出现行内代码时，
 *       {@code visit(Code)} 会把 {@code category} 从 {@code header_3} 改写成 {@code code_inline}，
 *       该小节的层级信息随之丢失。</li>
 * </ol>
 *
 * <p>自行解析还有一个附带好处：保留原始 Markdown 正文（列表、表格、代码块），
 * 这些结构对 RAG 是有语义价值的，而 MarkdownDocumentReader 会剥离它们
 * （默认配置下代码块甚至被整体丢弃，对技术文档是硬伤）。
 *
 * <p>已知限制：只支持 ATX 标题，不支持 setext 形式（{@code 标题\n===}）。
 */
public class MarkdownSectionParser {

    private static final Pattern ATX_HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern FENCE = Pattern.compile("^\\s*(`{3,}|~{3,})");
    private static final Pattern THEMATIC_BREAK = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");

    /**
     * 一个小节。{@code level}/{@code title} 为该小节自身的标题（文首无标题内容为 0/null），
     * {@code headingPath} 为含自身在内的完整路径。
     */
    public record Section(int level, String title, String headingPath, String body) {
    }

    private record HeadingEntry(int level, String title) {
    }

    public List<Section> parse(String markdown) {
        String normalized = OverlappingTextSplitter.normalize(markdown);
        if (normalized.isEmpty()) return List.of();

        List<Section> sections = new ArrayList<>();
        Deque<HeadingEntry> stack = new ArrayDeque<>();
        StringBuilder body = new StringBuilder();

        int currentLevel = 0;
        String currentTitle = null;
        String currentPath = "";

        boolean inFence = false;
        String fenceMarker = null;

        for (String line : normalized.split("\n", -1)) {
            Matcher fence = FENCE.matcher(line);
            if (fence.find()) {
                String marker = fence.group(1).substring(0, 1);
                if (!inFence) {
                    inFence = true;
                    fenceMarker = marker;
                } else if (marker.equals(fenceMarker)) {
                    inFence = false;
                    fenceMarker = null;
                }
                body.append(line).append('\n');
                continue;
            }

            // 围栏代码块内的 # 不是标题
            Matcher heading = inFence ? null : ATX_HEADING.matcher(line);
            if (heading != null && heading.matches()) {
                flush(sections, currentLevel, currentTitle, currentPath, body);

                currentLevel = heading.group(1).length();
                currentTitle = heading.group(2).strip();
                while (!stack.isEmpty() && stack.peekLast().level() >= currentLevel) {
                    stack.pollLast();
                }
                stack.addLast(new HeadingEntry(currentLevel, currentTitle));
                currentPath = stack.stream()
                        .map(HeadingEntry::title)
                        .filter(t -> t != null && !t.isBlank())
                        .collect(Collectors.joining(" > "));
                continue;
            }

            // 水平分割线对检索无价值，直接丢弃避免污染 chunk
            if (!inFence && THEMATIC_BREAK.matcher(line).matches()) continue;

            body.append(line).append('\n');
        }
        flush(sections, currentLevel, currentTitle, currentPath, body);
        return sections;
    }

    /**
     * 正文为空的标题<b>不产出小节</b>，但它已经压进栈了，
     * 后代小节仍能拿到完整路径 —— 这正是 MarkdownDocumentReader 做不到的地方。
     */
    private void flush(List<Section> out, int level, String title, String path, StringBuilder body) {
        String text = body.toString().strip();
        body.setLength(0);
        if (text.isEmpty()) return;
        out.add(new Section(level, title, path, text));
    }
}
