package org.javaup.ai.handler.impl;

import org.javaup.ai.handler.ReaderHandler;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.List;

@Component
public class TikaReaderHandler implements ReaderHandler {

    @Override
    public boolean canHandle(File file) {
        // 作为兜底策略，支持所有格式
        String name = file.getName().toLowerCase();
        return name.endsWith(".doc") || name.endsWith(".docx") 
            || name.endsWith(".ppt") || name.endsWith(".pptx")
            || name.endsWith(".xls") || name.endsWith(".xlsx");
    }

    @Override
    public List<Document> readhandle(File file) throws IOException {
        Resource resource = new FileSystemResource(file);
        return new TikaDocumentReader(resource).get();
    }
}