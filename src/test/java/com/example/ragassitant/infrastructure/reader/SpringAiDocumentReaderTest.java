package com.example.ragassitant.infrastructure.reader;

import com.example.ragassitant.domain.model.ChunkMetaKeys;
import com.example.ragassitant.domain.model.DocumentChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SpringAiDocumentReader 的 Markdown 章节路径还原测试。
 *
 * <p>纯单元测试，不需要 Spring 上下文。
 */
class SpringAiDocumentReaderTest {

    /**
     * 刻意包含真实文档里踩到过的坑（旧测试文档「每级标题都有正文」，掩盖了这些问题）：
     * <ul>
     *   <li>h1「计费管理」和 h2「账单生成」<b>没有直接正文</b> —— MarkdownDocumentReader 不会为它们 emit
     *       Document，祖先层级会整个丢失；</li>
     *   <li>「折扣规则」正文含<b>行内代码</b> —— 会把 MarkdownDocumentReader 的 category 覆盖成 code_inline；</li>
     *   <li>「账单查询」正文含<b>围栏代码块，且块内有 # 开头的行</b> —— 不能被误判成标题。</li>
     * </ul>
     */
    private static final String MARKDOWN = """
            # 计费管理

            ## 账单生成

            ### 结算规则

            结算规则的详细描述内容。

            ### 折扣规则

            折扣规则的详细描述内容，执行 `python manage.py apply_discount` 即可生效。

            ## 账单查询

            账单查询的说明内容。

            ```bash
            # 这是代码块里的注释，不是标题
            curl http://localhost:8900/api/bills
            ```

            ---

            # 设备管理

            设备管理模块的说明内容。
            """;

    /** barrier=6 表示每级标题都是屏障 → 完全不合并，用于单独验证章节路径还原 */
    private static final int NO_MERGE = 6;

    private SpringAiDocumentReader reader(boolean injectPrefix) {
        return reader(injectPrefix, NO_MERGE);
    }

    private SpringAiDocumentReader reader(boolean injectPrefix, int barrierLevel) {
        return new SpringAiDocumentReader(600, 90, 1, injectPrefix, barrierLevel);
    }

    private List<DocumentChunk> parse(boolean injectPrefix) {
        return parse(injectPrefix, NO_MERGE);
    }

    private List<DocumentChunk> parse(boolean injectPrefix, int barrierLevel) {
        return reader(injectPrefix, barrierLevel).readMarkdown(
                new ByteArrayInputStream(MARKDOWN.getBytes(StandardCharsets.UTF_8)), "billing.md");
    }

    private String pathOf(DocumentChunk c) {
        return String.valueOf(c.getMetadata().get(ChunkMetaKeys.HEADING_PATH));
    }

    @Test
    @DisplayName("祖先标题没有正文时，后代仍能拿到完整路径")
    void buildsFullHeadingPathWhenAncestorsHaveNoBody() {
        List<String> paths = parse(false).stream().map(this::pathOf).collect(Collectors.toList());

        // 「计费管理」「账单生成」都没有直接正文，但必须出现在后代路径里
        assertTrue(paths.contains("计费管理 > 账单生成 > 结算规则"), "实际路径: " + paths);
        assertTrue(paths.contains("计费管理 > 账单查询"), "实际路径: " + paths);
    }

    @Test
    @DisplayName("正文含行内代码不会丢失该小节的标题层级")
    void inlineCodeDoesNotBreakHeadingPath() {
        List<String> paths = parse(false).stream().map(this::pathOf).collect(Collectors.toList());
        assertTrue(paths.contains("计费管理 > 账单生成 > 折扣规则"),
                "含行内代码的小节层级丢失了，实际路径: " + paths);
    }

    @Test
    @DisplayName("同级标题正确出栈，不会把兄弟节点串成父子")
    void siblingHeadingsPopCorrectly() {
        List<String> paths = parse(false).stream().map(this::pathOf).collect(Collectors.toList());

        // 折扣规则 与 结算规则 是兄弟 h3，路径深度必须相同
        assertFalse(paths.contains("计费管理 > 账单生成 > 结算规则 > 折扣规则"), "兄弟标题被错误嵌套: " + paths);
        // 账单查询 是 h2，应回退到「计费管理」这一层
        assertFalse(paths.contains("计费管理 > 账单生成 > 账单查询"), "h2 未正确出栈: " + paths);
    }

    @Test
    @DisplayName("围栏代码块内以 # 开头的行不被误判为标题")
    void fencedCodeBlockIsNotParsedAsHeading() {
        List<DocumentChunk> chunks = parse(false);
        for (String p : chunks.stream().map(this::pathOf).toList()) {
            assertFalse(p.contains("这是代码块里的注释"), "代码块注释被当成标题: " + p);
        }
        String all = chunks.stream().map(DocumentChunk::getText).collect(Collectors.joining("\n"));
        assertTrue(all.contains("curl http://localhost:8900/api/bills"), "代码块内容丢失");
    }

    @Test
    @DisplayName("顶级标题切换时栈完全清空")
    void topLevelHeadingResetsStack() {
        List<String> paths = parse(false).stream().map(this::pathOf).collect(Collectors.toList());
        assertTrue(paths.contains("设备管理"), "实际路径: " + paths);
        assertTrue(paths.stream().noneMatch(p -> p.contains("计费管理 > 设备管理")), "h1 未清空栈: " + paths);
    }

    @Test
    @DisplayName("title 取末级标题，不再用 fileName 冒充")
    void titleIsSectionHeadingNotFileName() {
        List<DocumentChunk> chunks = parse(false);
        List<String> titles = chunks.stream()
                .map(c -> String.valueOf(c.getMetadata().get(ChunkMetaKeys.TITLE)))
                .distinct()
                .collect(Collectors.toList());

        assertTrue(titles.contains("结算规则"), "实际 title: " + titles);
        assertTrue(titles.size() > 1, "title 不该全库同值: " + titles);
        assertFalse(titles.stream().allMatch("billing.md"::equals), "title 仍是 fileName");
    }

    @Test
    @DisplayName("开启前缀注入时正文带章节路径，关闭时不带")
    void headingPrefixIsInjectedOnlyWhenEnabled() {
        DocumentChunk withPrefix = parse(true).stream()
                .filter(c -> "计费管理 > 账单生成 > 结算规则".equals(pathOf(c)))
                .findFirst().orElseThrow();
        assertTrue(withPrefix.getText().startsWith("【章节: 计费管理 > 账单生成 > 结算规则】"),
                "实际正文: " + withPrefix.getText());

        DocumentChunk without = parse(false).stream()
                .filter(c -> "计费管理 > 账单生成 > 结算规则".equals(pathOf(c)))
                .findFirst().orElseThrow();
        assertFalse(without.getText().contains("【章节:"), "关闭开关后仍注入了前缀");
    }

    @Test
    @DisplayName("前缀只注入章节路径，不注入文件名")
    void prefixDoesNotContainFileName() {
        for (DocumentChunk c : parse(true)) {
            assertFalse(c.getText().contains("billing.md"), "前缀污染了文件名: " + c.getText());
        }
    }

    @Test
    @DisplayName("每个 chunk 都带 fileName / chunkIndex / parentId")
    void basicMetadataIsPresent() {
        List<DocumentChunk> chunks = parse(true);
        assertFalse(chunks.isEmpty());
        for (DocumentChunk c : chunks) {
            assertNotNull(c.getId());
            assertEquals("billing.md", c.getMetadata().get(ChunkMetaKeys.FILE_NAME));
            assertNotNull(c.getMetadata().get(ChunkMetaKeys.CHUNK_INDEX));
            assertNotNull(c.getMetadata().get(ChunkMetaKeys.PARENT_ID));
        }
    }

    // ==================== 小节合并 ====================

    @Test
    @DisplayName("连续小节被合并，块数显著减少、平均长度显著提升")
    void adjacentSectionsAreMerged() {
        List<DocumentChunk> unmerged = parse(true, NO_MERGE);
        List<DocumentChunk> merged = parse(true, 1);

        assertTrue(merged.size() < unmerged.size(),
                "合并后块数未减少: 合并前=" + unmerged.size() + " 合并后=" + merged.size());

        double avgBefore = unmerged.stream().mapToInt(c -> c.getText().length()).average().orElse(0);
        double avgAfter = merged.stream().mapToInt(c -> c.getText().length()).average().orElse(0);
        assertTrue(avgAfter > avgBefore * 1.5,
                "平均块长度提升不明显: " + Math.round(avgBefore) + " -> " + Math.round(avgAfter));
    }

    @Test
    @DisplayName("默认屏障下不跨 h1 合并，两个顶级主题不会混进同一块")
    void doesNotMergeAcrossTopLevelHeadings() {
        for (DocumentChunk c : parse(true, 1)) {
            boolean hasBilling = c.getText().contains("计费管理");
            boolean hasDevice = c.getText().contains("设备管理");
            assertFalse(hasBilling && hasDevice,
                    "两个 h1 主题被合并进同一块:\n" + c.getText());
        }
    }

    @Test
    @DisplayName("合并块内每个小节仍保留自己的行内章节前缀")
    void mergedChunkKeepsPerSectionPrefixes() {
        DocumentChunk billing = parse(true, 1).stream()
                .filter(c -> c.getText().contains("结算规则"))
                .findFirst().orElseThrow();

        int prefixCount = billing.getText().split("【章节: ", -1).length - 1;
        assertTrue(prefixCount >= 2,
                "合并块内应保留多个小节前缀，实际只有 " + prefixCount + " 个:\n" + billing.getText());
    }

    @Test
    @DisplayName("合并块的 headingPath 取各小节的最长公共祖先")
    void mergedHeadingPathIsCommonAncestor() {
        DocumentChunk billing = parse(true, 1).stream()
                .filter(c -> c.getText().contains("结算规则"))
                .findFirst().orElseThrow();
        assertEquals("计费管理", pathOf(billing));
    }

    @Test
    @DisplayName("合并不丢内容：所有小节正文都能在某个块里找到")
    void mergeLosesNothing() {
        List<DocumentChunk> merged = parse(true, 1);
        String all = merged.stream().map(DocumentChunk::getText).collect(Collectors.joining("\n"));
        for (String fragment : List.of(
                "结算规则的详细描述内容",
                "折扣规则的详细描述内容",
                "账单查询的说明内容",
                "curl http://localhost:8900/api/bills",
                "设备管理模块的说明内容")) {
            assertTrue(all.contains(fragment), "合并后丢失内容: " + fragment);
        }
    }

    @Test
    @DisplayName("单个超长小节仍会被切分，不会产出巨块")
    void oversizedSectionStillSplits() {
        String longDoc = "# 大章节\n\n" + "这是一段很长的正文内容用于测试超长小节的切分行为。".repeat(40);
        List<DocumentChunk> chunks = new SpringAiDocumentReader(300, 40, 20, true, 1)
                .readMarkdown(new ByteArrayInputStream(longDoc.getBytes(StandardCharsets.UTF_8)), "long.md");

        assertTrue(chunks.size() > 1, "超长小节未被切分");
        for (DocumentChunk c : chunks) {
            assertTrue(c.getText().length() <= 400,
                    "切分后仍有巨块: " + c.getText().length() + " 字符");
        }
    }

    @Test
    @DisplayName("Tika 通道产出 chunk 且 headingPath 为空")
    void genericReaderProducesChunks() {
        List<DocumentChunk> chunks = reader(true).readGeneric(
                new ByteArrayInputStream("这是一段纯文本内容，用于验证 Tika 通道。".getBytes(StandardCharsets.UTF_8)),
                "plain.txt");
        assertFalse(chunks.isEmpty());
        assertEquals("", chunks.get(0).getMetadata().get(ChunkMetaKeys.HEADING_PATH));
        assertEquals("plain.txt", chunks.get(0).getMetadata().get(ChunkMetaKeys.TITLE));
    }
}
