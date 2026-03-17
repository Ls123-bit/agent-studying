package org.javaup.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.handler.ReaderHandlerContext;
import org.javaup.ai.split.OverlapParagraphTextSplit;
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
            System.out.println("分片前Document数量: " + docs.size());
            //使用TokenTextSplitter进行文档分片
            //List<Document> result = TokenTextSplitterSplit.split(docs);
            //使用自定义分片器：支持 chunkSize、overlap，并按段落拆分
            OverlapParagraphTextSplit split = new OverlapParagraphTextSplit(
                    // 每块最大300字符
                    300,
                    // 块之间重叠80字符
                    80    
            );
            List<Document> result = split.apply(docs);
            //TODO Spring AI Alibaba的递归分片的实现
            System.out.println("分片后Document数量: " + result.size());
            return result;
        } catch (Exception e) {
            log.error("处理文档失败: {}", file.getName(), e);
            throw new RuntimeException("文档处理失败: " + e.getMessage(), e);
        }
    }
}