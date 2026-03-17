package org.javaup.ai.handler;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.List;

@Component
public class ReaderHandlerContext {

    private final List<ReaderHandler> readerHandlerList;

    public ReaderHandlerContext(List<ReaderHandler> readerHandlerList) {
        this.readerHandlerList = readerHandlerList;
    }

    public List<Document> read(File file) throws IOException {
        ReaderHandler readerHandler = readerHandlerList.stream()
                .filter(handler -> handler.canHandle(file))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("此文件类型不支持，文件类型: " 
                        + file.getName()));
        return readerHandler.readhandle(file);
    }
}
