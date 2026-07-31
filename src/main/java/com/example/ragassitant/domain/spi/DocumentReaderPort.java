package com.example.ragassitant.domain.spi;

import com.example.ragassitant.domain.model.DocumentChunk;

import java.io.InputStream;
import java.util.List;

/**
 * 文档解析端口。
 *
 * <p>返回 {@link DocumentChunk} 而非 {@code List<String>}：旧签名会把解析器产出的结构信息
 * （Markdown 标题层级、PDF 页码）在这一层全部丢弃，导致下游只能用 fileName 冒充 title。
 * metadata 的 key 约定见 {@link com.example.ragassitant.domain.model.ChunkMetaKeys}。
 *
 * <p>实现方负责：切分、章节路径计算、前缀注入；调用方只负责补充 documentId / version 等业务元数据。
 */
public interface DocumentReaderPort {

    List<DocumentChunk> readMarkdown(InputStream input, String fileName);

    List<DocumentChunk> readPdf(InputStream input, String fileName);

    List<DocumentChunk> readGeneric(InputStream input, String fileName);
}
