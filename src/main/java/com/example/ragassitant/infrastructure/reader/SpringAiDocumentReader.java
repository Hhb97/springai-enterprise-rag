package com.example.ragassitant.infrastructure.reader;

import com.example.ragassitant.domain.spi.DocumentReaderPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Component
public class SpringAiDocumentReader implements DocumentReaderPort {

    private static final Logger log = LoggerFactory.getLogger(SpringAiDocumentReader.class);
    private static final int BUF = 8192;

    @Override
    public List<String> readMarkdown(InputStream input, String fileName) {
        log.info("📖 加载 Markdown 文档: {}", fileName);
        try {
            byte[] bytes = toBytes(input);
            MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                    .withHorizontalRuleCreateDocument(true)
                    .withIncludeCodeBlock(false)
                    .withIncludeBlockquote(false)
                    .build();
            MarkdownDocumentReader reader = new MarkdownDocumentReader(new ByteArrayResource(bytes, fileName), config);
            return flattenAndSplit(reader.get());
        } catch (Exception e) {
            throw new RuntimeException("读取 Markdown 失败: " + fileName, e);
        }
    }

    @Override
    public List<String> readPdf(InputStream input, String fileName) {
        log.info("📖 加载 PDF 文档: {}", fileName);
        try {
            byte[] bytes = toBytes(input);
            PagePdfDocumentReader reader = new PagePdfDocumentReader(new ByteArrayResource(bytes, fileName));
            return flattenAndSplit(reader.get());
        } catch (Exception e) {
            throw new RuntimeException("读取 PDF 失败: " + fileName, e);
        }
    }

    @Override
    public List<String> readGeneric(InputStream input, String fileName) {
        log.info("📖 加载文档 (Tika): {}", fileName);
        try {
            byte[] bytes = toBytes(input);
            TikaDocumentReader reader = new TikaDocumentReader(new ByteArrayResource(bytes, fileName));
            return flattenAndSplit(reader.get());
        } catch (Exception e) {
            throw new RuntimeException("读取文档失败 (Tika): " + fileName, e);
        }
    }

    private List<String> flattenAndSplit(List<Document> docs) {
        if (docs == null || docs.isEmpty()) return List.of();
        TokenTextSplitter splitter = new TokenTextSplitter();
        List<Document> chunks = splitter.apply(docs);
        List<String> result = new ArrayList<>(chunks.size());
        for (Document c : chunks) {
            if (c.getText() != null && !c.getText().isBlank()) result.add(c.getText());
        }
        return result;
    }

    private byte[] toBytes(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[BUF];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}