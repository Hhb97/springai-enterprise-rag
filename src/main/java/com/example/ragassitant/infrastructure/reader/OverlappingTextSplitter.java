package com.example.ragassitant.infrastructure.reader;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 支持重叠（overlap）的递归语义切分器。
 *
 * <p>替代 Spring AI 的 {@code TokenTextSplitter}，解决两个问题：
 * <ol>
 *   <li>{@code TokenTextSplitter} 不支持 overlap，跨块边界的语义被硬切断；</li>
 *   <li>它按 cl100k_base（OpenAI tokenizer）计量，与 nomic-embed-text 的分词对不上，
 *       中文场景误差尤其大 —— 这里统一改用<b>字符数</b>计量，中文下更稳定可控。</li>
 * </ol>
 *
 * <p>切分策略：按分隔符优先级递归降级，只有当一段仍超过 chunkSize 时才降到下一级。
 * <pre>
 *   段落(\n\n) → 换行(\n) → 中文句末(。！？；) → 英文句末(.!?;) → 强制按字符
 * </pre>
 *
 * <p>由于最小切分单位（atom）本身就是句子级的，overlap 直接由「回退整数个 atom」实现，
 * 天然对齐句子边界，不会出现半句开头。
 *
 * <p>本类无 Spring 依赖，可直接单元测试。
 */
public class OverlappingTextSplitter {

    /** 分隔符优先级表：匹配到的分隔符归属<b>前一段</b>（在 matcher.end() 处断开） */
    private static final Pattern[] SEPARATORS = {
            Pattern.compile("\\n[ \\t]*\\n\\s*"),              // L0 段落
            Pattern.compile("\\n\\s*"),                         // L1 换行
            Pattern.compile("[。！？；]+[\"'”’)）】]*\\s*"),     // L2 中文句末
            Pattern.compile("[.!?;]+[\"')\\]]*\\s+"),           // L3 英文句末
    };

    private final int chunkSizeChars;
    private final int overlapChars;
    private final int minChunkChars;

    public OverlappingTextSplitter(int chunkSizeChars, int overlapChars, int minChunkChars) {
        if (chunkSizeChars <= 0) {
            throw new IllegalArgumentException("chunkSizeChars 必须 > 0，当前=" + chunkSizeChars);
        }
        this.chunkSizeChars = chunkSizeChars;
        // overlap 必须严格小于 chunkSize，否则打包时无法前进会死循环；这里额外收紧到一半
        this.overlapChars = Math.max(0, Math.min(overlapChars, chunkSizeChars / 2));
        this.minChunkChars = Math.max(0, minChunkChars);
    }

    /**
     * 切分结果，带在<b>归一化后文本</b>中的字符区间，供 PDF 反查页码等场景使用。
     */
    public record Segment(String text, int start, int end) {
    }

    /**
     * 归一化：统一换行符、压缩 3 个以上连续空行、去首尾空白。
     * <p><b>幂等</b>——PDF 场景会先对每页归一化再拼接，随后切分时再次归一化不会移动偏移量。
     */
    public static String normalize(String text) {
        if (text == null) return "";
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    public List<String> splitToStrings(String text) {
        List<Segment> segments = split(text);
        List<String> result = new ArrayList<>(segments.size());
        for (Segment s : segments) result.add(s.text());
        return result;
    }

    /**
     * 切分文本。返回的每个 Segment 的 start/end 是相对 {@link #normalize(String)} 后文本的偏移量。
     */
    public List<Segment> split(String text) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) return List.of();

        // 1. 递归拆成原子片段（每个 atom 长度 <= chunkSize，且尽量落在语义边界上）
        List<int[]> atoms = new ArrayList<>();
        atomize(normalized, 0, normalized.length(), 0, atoms);
        if (atoms.isEmpty()) return List.of();

        // 2. 贪心打包成 chunk，记录每个 chunk 覆盖的 atom 下标区间 [from, to)
        List<int[]> chunkRanges = new ArrayList<>();
        int from = 0;
        while (from < atoms.size()) {
            int to = from;
            int len = 0;
            while (to < atoms.size()) {
                int atomLen = atoms.get(to)[1] - atoms.get(to)[0];
                // len == 0 时无条件收下首个 atom，防止超长 atom 导致空 chunk
                if (len > 0 && len + atomLen > chunkSizeChars) break;
                len += atomLen;
                to++;
            }
            chunkRanges.add(new int[]{from, to});

            if (to >= atoms.size()) break;

            // 3. 计算下一个 chunk 的起点：从末尾回退若干个完整 atom 作为 overlap
            int back = to;
            int budget = 0;
            while (back > from + 1) {
                int prevLen = atoms.get(back - 1)[1] - atoms.get(back - 1)[0];
                if (budget + prevLen > overlapChars) break;
                budget += prevLen;
                back--;
            }
            // back >= from + 1 恒成立，保证严格前进，不会死循环
            from = back;
        }

        // 4. 末块过短则并入前一块（直接扩展前块的 atom 上界，不会产生重复内容）
        if (chunkRanges.size() >= 2) {
            int[] last = chunkRanges.get(chunkRanges.size() - 1);
            if (charLen(atoms, last) < minChunkChars) {
                chunkRanges.get(chunkRanges.size() - 2)[1] = last[1];
                chunkRanges.remove(chunkRanges.size() - 1);
            }
        }

        // 5. 物化
        List<Segment> result = new ArrayList<>(chunkRanges.size());
        for (int[] range : chunkRanges) {
            int start = atoms.get(range[0])[0];
            int end = atoms.get(range[1] - 1)[1];
            // 收窄到首尾非空白字符，保证 start/end 与 text 精确对应
            int s = start, e = end;
            while (s < e && Character.isWhitespace(normalized.charAt(s))) s++;
            while (e > s && Character.isWhitespace(normalized.charAt(e - 1))) e--;
            if (s >= e) continue;
            // 只有一块时不受 minChunkChars 约束，避免短文档被整篇丢弃
            if (chunkRanges.size() > 1 && e - s < minChunkChars) continue;
            result.add(new Segment(normalized.substring(s, e), s, e));
        }
        return result;
    }

    private int charLen(List<int[]> atoms, int[] range) {
        return atoms.get(range[1] - 1)[1] - atoms.get(range[0])[0];
    }

    /**
     * 递归把 [start, end) 拆成若干不超过 chunkSize 的原子区间，结果按序 append 到 out。
     * 产出的区间连续且完整覆盖 [start, end)，因此偏移量是精确的。
     */
    private void atomize(String text, int start, int end, int level, List<int[]> out) {
        if (start >= end) return;

        if (end - start <= chunkSizeChars) {
            out.add(new int[]{start, end});
            return;
        }

        if (level >= SEPARATORS.length) {
            // 所有语义分隔符都用尽，强制按字符切
            for (int p = start; p < end; p += chunkSizeChars) {
                out.add(new int[]{p, Math.min(p + chunkSizeChars, end)});
            }
            return;
        }

        // 在当前级别的分隔符处断开；分隔符本身归属前一段
        List<Integer> cutPoints = new ArrayList<>();
        Matcher m = SEPARATORS[level].matcher(text);
        m.region(start, end);
        while (m.find()) {
            int cut = m.end();
            if (cut > start && cut < end) cutPoints.add(cut);
        }

        if (cutPoints.isEmpty()) {
            // 当前级别切不动，直接降级
            atomize(text, start, end, level + 1, out);
            return;
        }

        int prev = start;
        for (int cut : cutPoints) {
            atomize(text, prev, cut, level + 1, out);
            prev = cut;
        }
        atomize(text, prev, end, level + 1, out);
    }
}
