package com.example.ragassitant.application.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ingest 阶段的文件分派测试。
 *
 * <p>回归背景：改造前用 {@code classpath:docs/**}{@code /*.\{doc,docx,xls,xlsx,txt\}} 取「其他格式」文件，
 * 但 Spring 的 AntPathMatcher 不支持 shell 的花括号展开，会把 {@code \{...\}} 当作 URI 模板变量编译成
 * {@code (.*)}，导致该模式匹配任意后缀 —— .md/.pdf 被 Tika 二次读取，同一份文档以两种形态入库。
 */
class IngestFileDispatchTest {

    @Test
    @DisplayName("扩展名分派到正确的解析通道")
    void detectsFileTypeByExtension() {
        assertEquals("markdown", IngestAppService.detectFileType("手册.md"));
        assertEquals("markdown", IngestAppService.detectFileType("README.MARKDOWN"));
        assertEquals("pdf", IngestAppService.detectFileType("报表.PDF"));
        assertEquals("other", IngestAppService.detectFileType("数据.xlsx"));
        assertEquals("other", IngestAppService.detectFileType("说明.txt"));
    }

    @Test
    @DisplayName("不支持的扩展名返回 null 而不是当作 other 硬读")
    void rejectsUnsupportedExtensions() {
        assertNull(IngestAppService.detectFileType("image.png"));
        assertNull(IngestAppService.detectFileType("archive.zip"));
        assertNull(IngestAppService.detectFileType("noextension"));
        assertNull(IngestAppService.detectFileType("trailingdot."));
        assertNull(IngestAppService.detectFileType(""));
        assertNull(IngestAppService.detectFileType(null));
    }

    @Test
    @DisplayName("旧的花括号 glob 确实会误匹配 .md（记录 bug 成因）")
    void braceGlobOverMatches() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] matched = resolver.getResources("classpath:docs/**/*.{doc,docx,xls,xlsx,txt}");

        boolean matchedMarkdown = false;
        for (Resource r : matched) {
            String name = r.getFilename();
            if (name != null && name.toLowerCase().endsWith(".md")) matchedMarkdown = true;
        }
        assertTrue(matchedMarkdown,
                "花括号 glob 未误匹配 .md —— 若 Spring 行为已变更，本测试连同修复注释都需要复核");
    }

    @Test
    @DisplayName("单次枚举 + 扩展名分派下，每个文档只被处理一次")
    void singleEnumerationYieldsNoDuplicates() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

        List<String> processed = new ArrayList<>();
        for (Resource resource : resolver.getResources("classpath:docs/**/*")) {
            if (!resource.isReadable()) continue;
            if (IngestAppService.detectFileType(resource.getFilename()) == null) continue;
            processed.add(resource.getURI().toString());
        }

        assertFalse(processed.isEmpty(), "classpath:docs 下没有可处理文档，测试失去意义");
        assertEquals(processed.size(), processed.stream().distinct().count(),
                "同一资源被处理多次: " + processed);

        long markdownCount = processed.stream().filter(u -> u.toLowerCase().endsWith(".md")).count();
        assertEquals(1, markdownCount, "示例文档应只被处理一次，实际: " + processed);
    }
}
