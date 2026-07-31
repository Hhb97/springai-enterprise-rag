package com.example.ragassitant.infrastructure.reader;

import com.example.ragassitant.domain.model.ChunkMetaKeys;
import com.example.ragassitant.domain.model.DocumentChunk;
import com.example.ragassitant.domain.spi.DocumentReaderPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * DocumentReaderPort 的 Spring AI 实现。
 *
 * <p>相比改造前的三点变化：
 * <ol>
 *   <li>不再丢弃解析器的 metadata —— Markdown 的标题层级、PDF 的页码都保留下来；</li>
 *   <li>用 {@link OverlappingTextSplitter} 替换 {@code TokenTextSplitter}，按字符计量且带 overlap；</li>
 *   <li>入库文本前置章节路径（contextual header），让 embedding 与关键词都能命中标题词。</li>
 * </ol>
 */
@Component
public class SpringAiDocumentReader implements DocumentReaderPort {

    private static final Logger log = LoggerFactory.getLogger(SpringAiDocumentReader.class);
    private static final int BUF = 8192;

    private static final String META_PDF_PAGE = PagePdfDocumentReader.METADATA_START_PAGE_NUMBER;

    private final MarkdownSectionParser markdownParser = new MarkdownSectionParser();
    private final OverlappingTextSplitter splitter;
    private final boolean injectHeadingPrefix;
    private final int chunkSizeChars;
    private final int mergeBarrierLevel;

    public SpringAiDocumentReader(
            @Value("${rag.chunking.chunk-size-chars:600}") int chunkSizeChars,
            @Value("${rag.chunking.overlap-chars:90}") int overlapChars,
            @Value("${rag.chunking.min-chunk-chars:50}") int minChunkChars,
            @Value("${rag.chunking.inject-heading-prefix:true}") boolean injectHeadingPrefix,
            @Value("${rag.chunking.merge-barrier-level:1}") int mergeBarrierLevel) {
        this.splitter = new OverlappingTextSplitter(chunkSizeChars, overlapChars, minChunkChars);
        this.injectHeadingPrefix = injectHeadingPrefix;
        this.chunkSizeChars = chunkSizeChars;
        this.mergeBarrierLevel = mergeBarrierLevel;
        log.info("🔧 文档切分配置: chunkSize={}字符, overlap={}字符, minChunk={}字符, 注入章节前缀={}, 合并屏障=h{}",
                chunkSizeChars, overlapChars, minChunkChars, injectHeadingPrefix, mergeBarrierLevel);
    }

    /** 一个 Markdown 小节：解析出的标题路径 + 已注入前缀的正文 */
    private record Section(String headingPath, String title, int level, String text) {
    }

    // ==================== Markdown ====================

    /**
     * 用 {@link MarkdownSectionParser} 自行解析 ATX 标题层级 —— 不再依赖 MarkdownDocumentReader，
     * 原因见该类的 Javadoc（无正文的标题不 emit、category 会被行内代码覆盖）。
     */
    @Override
    public List<DocumentChunk> readMarkdown(InputStream input, String fileName) {
        log.info("📖 加载 Markdown 文档: {}", fileName);
        try {
            String markdown = new String(toBytes(input), StandardCharsets.UTF_8);
            List<Section> sections = markdownParser.parse(markdown).stream()
                    .map(s -> new Section(
                            s.headingPath(),
                            s.title() == null || s.title().isBlank() ? fileName : s.title(),
                            s.level(),
                            withHeadingPrefix(s.headingPath(), s.body())))
                    .toList();

            List<DocumentChunk> chunks = mergeAndSplit(sections, fileName);
            logSample(fileName, chunks);
            return chunks;
        } catch (Exception e) {
            throw new RuntimeException("读取 Markdown 失败: " + fileName, e);
        }
    }

    /**
     * 先合并、再切分。
     *
     * <p>Markdown 按标题逐节解析，若对每个小节独立切分，「权限配置」这类只有几十字的小节会各自成块
     * —— 实测一份 1.7KB 的文档切出 9 块、平均仅 98 字符，chunk-size 预算完全没用上，
     * 块太小反而丢失上下文。
     *
     * <p>因此这里先按预算贪心合并<b>连续</b>小节，每个小节保留自己的行内章节前缀（块仍自描述），
     * 默认不跨 h{@code mergeBarrierLevel} 合并，避免把两个顶级主题混进同一块。
     * 单个小节本身超预算时，仍退回 overlap 切分。
     */
    private List<DocumentChunk> mergeAndSplit(List<Section> sections, String fileName) {
        List<DocumentChunk> chunks = new ArrayList<>();
        int chunkIndex = 0;

        List<Section> buffer = new ArrayList<>();
        int bufferLen = 0;

        for (Section section : sections) {
            boolean barrier = mergeBarrierLevel > 0
                    && section.level() > 0
                    && section.level() <= mergeBarrierLevel
                    && !buffer.isEmpty();
            boolean wouldOverflow = bufferLen + section.text().length() > chunkSizeChars;

            if (barrier || wouldOverflow) {
                chunkIndex = flushBuffer(buffer, chunks, fileName, chunkIndex);
                buffer.clear();
                bufferLen = 0;
            }

            buffer.add(section);
            bufferLen += section.text().length();
        }
        flushBuffer(buffer, chunks, fileName, chunkIndex);
        return chunks;
    }

    /** 把缓冲区里的小节物化成 chunk；整体超预算时（单个超长小节）才走 overlap 切分 */
    private int flushBuffer(List<Section> buffer, List<DocumentChunk> out, String fileName, int chunkIndex) {
        if (buffer.isEmpty()) return chunkIndex;

        Section first = buffer.get(0);
        String merged = buffer.stream().map(Section::text).collect(Collectors.joining("\n\n"));
        String parentId = UUID.randomUUID().toString();

        List<String> parts = merged.length() <= chunkSizeChars
                ? List.of(merged)
                : splitter.splitToStrings(merged);

        for (String part : parts) {
            Map<String, Object> meta = baseMeta(fileName, first.title(), chunkIndex, parentId);
            meta.put(ChunkMetaKeys.HEADING_PATH, commonAncestorPath(buffer));
            meta.put(ChunkMetaKeys.HEADING_LEVEL, first.level());
            out.add(newChunk(part, meta));
            chunkIndex++;
        }
        return chunkIndex;
    }

    /** 合并块的 headingPath 取各小节路径的最长公共前缀（按 " > " 分段比较） */
    private String commonAncestorPath(List<Section> buffer) {
        List<String> common = null;
        for (Section s : buffer) {
            List<String> parts = s.headingPath() == null || s.headingPath().isBlank()
                    ? List.of()
                    : List.of(s.headingPath().split(" > "));
            if (common == null) {
                common = new ArrayList<>(parts);
                continue;
            }
            int keep = 0;
            while (keep < common.size() && keep < parts.size() && common.get(keep).equals(parts.get(keep))) keep++;
            common = new ArrayList<>(common.subList(0, keep));
        }
        return common == null ? "" : String.join(" > ", common);
    }

    // ==================== PDF ====================

    /**
     * PagePdfDocumentReader 按页产出 Document，一段话跨页就会被硬切断。
     * 这里先把各页归一化后<b>拼成整篇</b>再统一切分，切完用字符偏移量反查页码，
     * 从而同时拿到「语义连续」与「页码溯源」。
     *
     * <p>依赖 {@link OverlappingTextSplitter#normalize} 的幂等性：各页先归一化再以 "\n\n" 拼接，
     * 切分时内部再次归一化为空操作，偏移量不会漂移。
     */
    @Override
    public List<DocumentChunk> readPdf(InputStream input, String fileName) {
        log.info("📖 加载 PDF 文档: {}", fileName);
        try {
            byte[] bytes = toBytes(input);
            PagePdfDocumentReader reader = new PagePdfDocumentReader(new ByteArrayResource(bytes, fileName));
            List<Document> pages = new ArrayList<>(reader.get());
            pages.sort(Comparator.comparingInt(d -> pageNumberOf(d, Integer.MAX_VALUE)));

            StringBuilder whole = new StringBuilder();
            // pageStarts[i] = {该页在 whole 中的起始偏移, 页码}，按偏移量升序
            List<int[]> pageStarts = new ArrayList<>();
            for (Document page : pages) {
                String pageText = OverlappingTextSplitter.normalize(page.getText());
                if (pageText.isEmpty()) continue;
                if (whole.length() > 0) whole.append("\n\n");
                pageStarts.add(new int[]{whole.length(), pageNumberOf(page, 0)});
                whole.append(pageText);
            }

            List<DocumentChunk> chunks = new ArrayList<>();
            int chunkIndex = 0;
            String parentId = UUID.randomUUID().toString();
            for (OverlappingTextSplitter.Segment seg : splitter.split(whole.toString())) {
                Map<String, Object> chunkMeta = baseMeta(fileName, fileName, chunkIndex, parentId);
                chunkMeta.put(ChunkMetaKeys.HEADING_PATH, "");
                chunkMeta.put(ChunkMetaKeys.PAGE_NUMBER, lookupPage(pageStarts, seg.start()));
                chunks.add(newChunk(seg.text(), chunkMeta));
                chunkIndex++;
            }
            logSample(fileName, chunks);
            return chunks;
        } catch (Exception e) {
            throw new RuntimeException("读取 PDF 失败: " + fileName, e);
        }
    }

    // ==================== Word / Excel / 纯文本（Tika） ====================

    @Override
    public List<DocumentChunk> readGeneric(InputStream input, String fileName) {
        log.info("📖 加载文档 (Tika): {}", fileName);
        try {
            byte[] bytes = toBytes(input);
            TikaDocumentReader reader = new TikaDocumentReader(new ByteArrayResource(bytes, fileName));

            List<DocumentChunk> chunks = new ArrayList<>();
            int chunkIndex = 0;
            for (Document doc : reader.get()) {
                String parentId = UUID.randomUUID().toString();
                for (String part : splitter.splitToStrings(doc.getText())) {
                    Map<String, Object> chunkMeta = baseMeta(fileName, fileName, chunkIndex, parentId);
                    chunkMeta.put(ChunkMetaKeys.HEADING_PATH, "");
                    chunks.add(newChunk(part, chunkMeta));
                    chunkIndex++;
                }
            }
            logSample(fileName, chunks);
            return chunks;
        } catch (Exception e) {
            throw new RuntimeException("读取文档失败 (Tika): " + fileName, e);
        }
    }

    // ==================== 内部工具 ====================

    /**
     * 注入 contextual header。只注入章节路径、<b>不注入文件名</b> ——
     * 文件名对语义无贡献，却会让同一文档的所有 chunk 向量高度趋同。
     */
    private String withHeadingPrefix(String headingPath, String body) {
        if (!injectHeadingPrefix || headingPath == null || headingPath.isBlank()) return body;
        return "【章节: " + headingPath + "】\n" + body;
    }

    private Map<String, Object> baseMeta(String fileName, String title, int chunkIndex, String parentId) {
        Map<String, Object> meta = new HashMap<>();
        meta.put(ChunkMetaKeys.FILE_NAME, fileName);
        meta.put(ChunkMetaKeys.TITLE, title);
        meta.put(ChunkMetaKeys.CHUNK_INDEX, chunkIndex);
        meta.put(ChunkMetaKeys.PARENT_ID, parentId);
        return meta;
    }

    private DocumentChunk newChunk(String text, Map<String, Object> metadata) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(UUID.randomUUID().toString());
        chunk.setText(text);
        chunk.setMetadata(metadata);
        return chunk;
    }

    private int pageNumberOf(Document doc, int defaultValue) {
        Object raw = doc.getMetadata() == null ? null : doc.getMetadata().get(META_PDF_PAGE);
        return raw instanceof Number n ? n.intValue() : defaultValue;
    }

    /** 二分查找 offset 落在哪一页（pageStarts 按起始偏移升序） */
    private int lookupPage(List<int[]> pageStarts, int offset) {
        if (pageStarts.isEmpty()) return 0;
        int lo = 0, hi = pageStarts.size() - 1, ans = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (pageStarts.get(mid)[0] <= offset) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return pageStarts.get(ans)[1];
    }

    /** 切分质量靠人工核对，这里打印前 3 块的章节路径与正文摘要 */
    private void logSample(String fileName, List<DocumentChunk> chunks) {
        log.info("✂️ {} 切分完成，共 {} 块", fileName, chunks.size());
        for (int i = 0; i < Math.min(3, chunks.size()); i++) {
            DocumentChunk c = chunks.get(i);
            String text = c.getText();
            log.info("   [{}] headingPath='{}' len={} | {}",
                    i,
                    c.getMetadata().get(ChunkMetaKeys.HEADING_PATH),
                    text.length(),
                    text.substring(0, Math.min(100, text.length())).replace("\n", " ⏎ "));
        }
    }

    private byte[] toBytes(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[BUF];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
