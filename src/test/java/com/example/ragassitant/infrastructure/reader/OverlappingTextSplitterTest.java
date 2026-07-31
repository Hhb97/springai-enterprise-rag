package com.example.ragassitant.infrastructure.reader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OverlappingTextSplitter 纯算法单测（无 Spring 上下文）。
 */
class OverlappingTextSplitterTest {

    private static String repeatSentences(String sentence, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) sb.append(sentence);
        return sb.toString();
    }

    @Test
    @DisplayName("短文本不切分，且不受 minChunkChars 约束")
    void shortTextStaysWhole() {
        OverlappingTextSplitter splitter = new OverlappingTextSplitter(600, 90, 50);
        List<String> chunks = splitter.splitToStrings("很短的一句话。");
        assertEquals(1, chunks.size());
        assertEquals("很短的一句话。", chunks.get(0));
    }

    @Test
    @DisplayName("空白/null 输入返回空列表")
    void blankInputYieldsNothing() {
        OverlappingTextSplitter splitter = new OverlappingTextSplitter(600, 90, 50);
        assertTrue(splitter.splitToStrings(null).isEmpty());
        assertTrue(splitter.splitToStrings("   \n\n  ").isEmpty());
    }

    @Test
    @DisplayName("每个 chunk 不超过 chunkSize（除末块合并造成的轻微溢出）")
    void chunksRespectSizeLimit() {
        OverlappingTextSplitter splitter = new OverlappingTextSplitter(100, 20, 10);
        String text = repeatSentences("这是一段用于测试切分逻辑的中文句子。", 40);
        List<String> chunks = splitter.splitToStrings(text);
        assertTrue(chunks.size() > 1, "长文本应被切成多块");
        for (int i = 0; i < chunks.size() - 1; i++) {
            assertTrue(chunks.get(i).length() <= 100,
                    "第 " + i + " 块超长: " + chunks.get(i).length());
        }
    }

    @Test
    @DisplayName("相邻 chunk 存在重叠内容")
    void adjacentChunksOverlap() {
        OverlappingTextSplitter splitter = new OverlappingTextSplitter(100, 30, 10);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) sb.append("第").append(i).append("句话的内容在这里。");
        List<String> chunks = splitter.splitToStrings(sb.toString());

        assertTrue(chunks.size() >= 3);
        for (int i = 0; i < chunks.size() - 1; i++) {
            String cur = chunks.get(i);
            String next = chunks.get(i + 1);
            String tail = cur.substring(Math.max(0, cur.length() - 30));
            boolean overlaps = false;
            // 取当前块末尾的一段，应能在下一块开头找到
            for (int len = Math.min(tail.length(), next.length()); len >= 4; len--) {
                if (next.startsWith(cur.substring(cur.length() - len))) {
                    overlaps = true;
                    break;
                }
            }
            assertTrue(overlaps, "第 " + i + " 块与下一块无重叠\n cur尾=" + tail + "\n next首=" + next.substring(0, Math.min(40, next.length())));
        }
    }

    @Test
    @DisplayName("overlap 起点对齐句子边界，不会半句开头")
    void overlapAlignsToSentenceBoundary() {
        OverlappingTextSplitter splitter = new OverlappingTextSplitter(120, 40, 10);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) sb.append("句子").append(i).append("的完整内容如下所述。");
        List<String> chunks = splitter.splitToStrings(sb.toString());

        for (int i = 1; i < chunks.size(); i++) {
            assertTrue(chunks.get(i).startsWith("句子"),
                    "第 " + i + " 块从半句开始: " + chunks.get(i).substring(0, Math.min(20, chunks.get(i).length())));
        }
    }

    @Test
    @DisplayName("超长无分隔符文本被强制按字符切且不丢内容")
    void forceSplitsTextWithoutSeparators() {
        OverlappingTextSplitter splitter = new OverlappingTextSplitter(50, 0, 5);
        String text = "A".repeat(237);
        List<String> chunks = splitter.splitToStrings(text);

        assertTrue(chunks.size() >= 4);
        for (String c : chunks) assertTrue(c.length() <= 50, "强制切分后仍超长: " + c.length());
        assertEquals(237, String.join("", chunks).length(), "overlap=0 时拼接应还原原文长度");
    }

    @Test
    @DisplayName("优先按段落切，段落完整保留")
    void prefersParagraphBoundary() {
        OverlappingTextSplitter splitter = new OverlappingTextSplitter(60, 0, 5);
        String text = "第一段的内容。\n\n第二段的内容。\n\n第三段的内容。";
        List<String> chunks = splitter.splitToStrings(text);
        assertEquals(1, chunks.size(), "总长未超 chunkSize 时不应切分");

        OverlappingTextSplitter small = new OverlappingTextSplitter(10, 0, 3);
        List<String> smallChunks = small.splitToStrings(text);
        assertEquals(3, smallChunks.size());
        assertEquals("第一段的内容。", smallChunks.get(0));
        assertEquals("第二段的内容。", smallChunks.get(1));
        assertEquals("第三段的内容。", smallChunks.get(2));
    }

    @Test
    @DisplayName("Segment 的 start/end 偏移量精确对应归一化文本")
    void offsetsMatchNormalizedText() {
        OverlappingTextSplitter splitter = new OverlappingTextSplitter(80, 20, 10);
        String raw = "标题一\r\n\r\n正文内容第一句。正文内容第二句。\n\n\n\n第二段落的正文内容在此处展开描述。";
        String normalized = OverlappingTextSplitter.normalize(raw);

        for (OverlappingTextSplitter.Segment seg : splitter.split(raw)) {
            assertEquals(seg.text(), normalized.substring(seg.start(), seg.end()),
                    "偏移量与文本不一致: [" + seg.start() + "," + seg.end() + ")");
        }
    }

    @Test
    @DisplayName("normalize 幂等（PDF 拼页后再切分不会移动偏移量）")
    void normalizeIsIdempotent() {
        String raw = "  第一行  \r\n\r\n\r\n第二行\t\n\n\n\n第三行  ";
        String once = OverlappingTextSplitter.normalize(raw);
        assertEquals(once, OverlappingTextSplitter.normalize(once));
    }

    @Test
    @DisplayName("overlap >= chunkSize 时被收紧，不会死循环")
    void oversizedOverlapDoesNotHang() {
        OverlappingTextSplitter splitter = new OverlappingTextSplitter(50, 500, 5);
        String text = repeatSentences("测试句子内容。", 50);
        List<String> chunks = assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(5), () -> splitter.splitToStrings(text));
        assertFalse(chunks.isEmpty());
    }

    @Test
    @DisplayName("chunkSize <= 0 直接拒绝")
    void rejectsInvalidChunkSize() {
        assertThrows(IllegalArgumentException.class, () -> new OverlappingTextSplitter(0, 10, 5));
    }

    @Test
    @DisplayName("末块过短时并入前一块，内容不丢失")
    void shortTailIsMergedIntoPrevious() {
        OverlappingTextSplitter splitter = new OverlappingTextSplitter(40, 0, 30);
        String text = repeatSentences("完整的测试句子内容在这里。", 6) + "短尾。";
        List<String> chunks = splitter.splitToStrings(text);
        assertTrue(chunks.get(chunks.size() - 1).contains("短尾。"), "末尾短内容被丢弃了");
    }
}
