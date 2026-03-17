package org.javaup.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.handler.ReaderHandlerContext;
import org.javaup.ai.util.DocumentClearHandler;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;

@Slf4j
@Service
public class DocumentPreprocessService {

    private final ReaderHandlerContext readerHandlerContext;

    public DocumentPreprocessService(ReaderHandlerContext readerHandlerContext) {
        this.readerHandlerContext = readerHandlerContext;
    }

    /**
     * 处理单个文件
     */
    public List<Document> process(File file) {
        try {
            // 1. 读取文档
            log.info("开始读取文档: {}", file.getName());
            List<Document> docs = readerHandlerContext.read(file);
            log.info("读取完成，共 {} 个Document", docs.size());

            // 2. 清洗文档
            log.info("开始清洗文档");
            docs = DocumentClearHandler.clearDocuments(docs);
            log.info("清洗完成");

            // 3. 添加元数据
            log.info("添加元数据");
            for (Document doc : docs) {
                doc.getMetadata().put("filename", file.getName());
                doc.getMetadata().put("processTime", System.currentTimeMillis());
            }
            return docs;
            //使用TokenTextSplitter进行文档分片
//            TokenTextSplitter splitter = new TokenTextSplitter(
//                    // 每块最多600 tokens
//                    600,
//                    // 每块至少300字符再考虑断点
//                    300,
//                    // 太短的不做嵌入
//                    5,
//                    // 最多拆分8000块
//                    8000,
//                    // 保留句号、换行符
//                    true   
//            );
//            return splitter.apply(docs);
        } catch (Exception e) {
            log.error("处理文档失败: {}", file.getName(), e);
            throw new RuntimeException("文档处理失败: " + e.getMessage(), e);
        }
    }
}