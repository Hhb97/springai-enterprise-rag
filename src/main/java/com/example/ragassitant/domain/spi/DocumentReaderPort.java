package com.example.ragassitant.domain.spi;

import java.io.InputStream;
import java.util.List;

public interface DocumentReaderPort {

    List<String> readMarkdown(InputStream input, String fileName);

    List<String> readPdf(InputStream input, String fileName);

    List<String> readGeneric(InputStream input, String fileName);
}