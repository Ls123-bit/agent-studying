package org.javaup.ai.split;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.List;

public class TokenTextSplitterSplit {
    
    public static List<Document> split(List<Document> documents) {
        if (CollectionUtils.isEmpty(documents)) {
            return Collections.emptyList();
        }
        
        //使用TokenTextSplitter进行文档分片
        TokenTextSplitter splitter = new TokenTextSplitter(
                // 每块最多600 tokens
                600,
                // 每块至少300字符再考虑断点
                300,
                // 太短的不做嵌入
                5,
                // 最多拆分8000块
                8000,
                // 保留句号、换行符
                true
        );
        
        return splitter.apply(documents);
    }
}
