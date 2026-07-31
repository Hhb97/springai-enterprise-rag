package com.example.ragassitant.domain.model;

/**
 * DocumentChunk metadata 的统一 key 约定。
 *
 * <p>改造前 metadata 只有 fileName / title（且 title 直接用 fileName 冒充，全库同值，
 * 导致关键词检索里的 title 加权形同虚设）。这里把切分阶段能拿到的结构信息固化成契约，
 * 供检索加权、溯源展示、后续 rerank / small-to-big 检索复用。
 */
public final class ChunkMetaKeys {

    private ChunkMetaKeys() {
    }

    /** 原始文件名，如 acucloud-billing.md */
    public static final String FILE_NAME = "fileName";

    /** 所属章节的末级标题（Markdown 取 heading 文本；PDF/其他退化为文件名） */
    public static final String TITLE = "title";

    /** 完整章节路径，如「计费管理 &gt; 账单生成 &gt; 结算规则」；无标题结构时为空串 */
    public static final String HEADING_PATH = "headingPath";

    /** Markdown 标题层级（1~6）；非 Markdown 为 0 */
    public static final String HEADING_LEVEL = "headingLevel";

    /** PDF 页码；非 PDF 不写入 */
    public static final String PAGE_NUMBER = "pageNumber";

    /** 该 chunk 在整篇文档内的顺序号，从 0 开始 */
    public static final String CHUNK_INDEX = "chunkIndex";

    /** 同一原始 section 切出的多个 chunk 共享的 id，为将来 small-to-big 检索预留 */
    public static final String PARENT_ID = "parentId";

    /** 文档逻辑 id（由 IngestAppService 从版本表补入） */
    public static final String DOCUMENT_ID = "documentId";

    /** 文档版本号（由 IngestAppService 补入） */
    public static final String VERSION = "version";

    /** 检索通道标记，如 HYBRID_LINEAR_WEIGHT / VECTOR_ONLY，由检索层写入 */
    public static final String RETRIEVAL_MODE = "retrievalMode";

    /** 混合检索融合后的得分，由检索层写入 */
    public static final String HYBRID_SCORE = "hybridScore";
}
