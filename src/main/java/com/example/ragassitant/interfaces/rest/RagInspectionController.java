package com.example.ragassitant.interfaces.rest;

import com.example.ragassitant.application.service.RagAppService;
import com.example.ragassitant.domain.model.ChunkMetaKeys;
import com.example.ragassitant.domain.model.DocumentChunk;
import com.example.ragassitant.interfaces.dto.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.*;

/**
 * RAG 调优用的两个观察窗口。调召回率之前得先看清两件事：
 * <ol>
 *   <li><b>切出来的块长什么样</b>（{@code /chunks}）：块太碎、章节前缀丢失、某文档一块都没入库，
 *       这些问题光看回答是看不出来的；</li>
 *   <li><b>某个问题实际召回了什么、各自多少分</b>（{@code /retrieval}）：决定 similarity-threshold、
 *       top-k、混合权重该往哪边调，靠的就是这些分数分布。</li>
 * </ol>
 * 两个接口都只读，不调用 LLM。
 */
@RestController
@RequestMapping("/api/rag")
public class RagInspectionController {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int PREVIEW = 200;

    private final RagAppService ragAppService;
    private final String uploadDir;
    private final int minChunkChars;

    public RagInspectionController(RagAppService ragAppService,
                                   @Value("${storage.upload-dir:./uploads}") String uploadDir,
                                   @Value("${rag.chunking.min-chunk-chars:50}") int minChunkChars) {
        this.ragAppService = ragAppService;
        this.uploadDir = uploadDir;
        this.minChunkChars = minChunkChars;
    }

    /** 某个问题的召回明细：排名、向量分、混合分、是否为低于阈值的保底块、来源。 */
    @GetMapping("/retrieval")
    public ApiResponse<Map<String, Object>> retrieval(@RequestParam("q") String question,
                                                      @RequestParam(value = "topK", defaultValue = "0") int topK,
                                                      @RequestParam(value = "hybrid", defaultValue = "true") boolean hybrid) {
        List<DocumentChunk> docs = ragAppService.retrieveDocuments(question, hybrid, topK);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            DocumentChunk d = docs.get(i);
            Map<String, Object> meta = d.getMetadata() == null ? Map.of() : d.getMetadata();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", i + 1);
            row.put("vectorScore", d.getScore());
            row.put("hybridScore", meta.get("hybridScore"));
            row.put("belowThreshold", Boolean.TRUE.equals(meta.get("belowThreshold")));
            row.put("fileName", meta.get(ChunkMetaKeys.FILE_NAME));
            row.put("headingPath", meta.get(ChunkMetaKeys.HEADING_PATH));
            row.put("chars", d.getText() == null ? 0 : d.getText().length());
            row.put("preview", preview(d.getText()));
            rows.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("question", question);
        data.put("hybrid", hybrid);
        data.put("count", rows.size());
        data.put("chunks", rows);
        return ApiResponse.ok("召回明细", data);
    }

    /**
     * 向量库里的块：按文件统计数量与长度，并列出（可按文件名过滤）。
     * 直接读本地 SimpleVectorStore 的持久化文件，不加载 embedding 到响应里。
     */
    @GetMapping("/chunks")
    public ApiResponse<Map<String, Object>> chunks(@RequestParam(value = "fileName", required = false) String fileName,
                                                   @RequestParam(value = "limit", defaultValue = "50") int limit) {
        File store = new File(uploadDir, "vectorstore.json");
        if (!store.isFile()) {
            return ApiResponse.fail("VECTOR_STORE_EMPTY", "向量库文件不存在: " + store.getPath());
        }
        JsonNode root;
        try {
            root = JSON.readTree(store);
        } catch (Exception e) {
            return ApiResponse.fail("VECTOR_STORE_UNREADABLE", "向量库文件解析失败: " + e.getMessage());
        }

        Map<String, int[]> perFile = new TreeMap<>(); // [块数, 总字符, 过短块数]
        List<Map<String, Object>> rows = new ArrayList<>();
        Iterator<JsonNode> it = root.isObject() ? root.elements() : root.iterator();
        while (it.hasNext()) {
            JsonNode n = it.next();
            String text = n.path("text").asText("");
            JsonNode meta = n.path("metadata");
            String file = meta.path(ChunkMetaKeys.FILE_NAME).asText("(unknown)");
            int[] agg = perFile.computeIfAbsent(file, k -> new int[3]);
            agg[0]++;
            agg[1] += text.length();
            if (text.length() < minChunkChars) agg[2]++;

            if ((fileName == null || file.contains(fileName)) && rows.size() < Math.max(1, limit)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", n.path("id").asText(null));
                row.put("fileName", file);
                row.put("headingPath", meta.path(ChunkMetaKeys.HEADING_PATH).asText(null));
                row.put("chars", text.length());
                row.put("preview", preview(text));
                rows.add(row);
            }
        }

        List<Map<String, Object>> files = new ArrayList<>();
        perFile.forEach((f, a) -> files.add(Map.of(
                "fileName", f, "chunks", a[0],
                "avgChars", a[0] == 0 ? 0 : a[1] / a[0],
                "tooShort", a[2])));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalChunks", perFile.values().stream().mapToInt(a -> a[0]).sum());
        data.put("files", files);
        data.put("chunks", rows);
        return ApiResponse.ok("向量库块明细", data);
    }

    private static String preview(String text) {
        if (text == null) return "";
        return text.length() <= PREVIEW ? text : text.substring(0, PREVIEW) + "…";
    }
}
