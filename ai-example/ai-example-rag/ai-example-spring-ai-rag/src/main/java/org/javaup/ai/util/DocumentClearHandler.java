package org.javaup.ai.util;

import org.apache.commons.collections4.CollectionUtils;
import org.springframework.ai.document.Document;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


public class DocumentClearHandler {
    
    
    public static List<Document> clearDocuments(List<Document> documents) {
        if (CollectionUtils.isEmpty(documents)) {
            return documents;
        }
        
        return documents.stream()
                .map(doc -> {
                    if (doc == null || doc.getText() == null) {
                        return doc;
                    }
                    
                    String text = doc.getText();
                    
                    // 1. 去掉多余空白字符
                    text = text.replaceAll("\\s+", " ").trim();
                    
                    // 2. 去掉无意义的乱码或特殊符号
                    text = text.replaceAll("[^\\p{L}\\p{N}\\p{P}\\p{Z}\\n]", "");
                    
                    // 3. 去除重复段落
                    String[] paragraphs = text.split("\\n+");
                    Set<String> seen = new LinkedHashSet<>();
                    for (String para : paragraphs) {
                        String trimmed = para.trim();
                        if (!trimmed.isEmpty()) {
                            seen.add(trimmed);
                        }
                    }
                    text = String.join("\n", seen);
                    
                    // 4. 重新创建Document，保留原有的元数据
                    return new Document(text, doc.getMetadata());
                })
                .collect(Collectors.toList());
    }
}
