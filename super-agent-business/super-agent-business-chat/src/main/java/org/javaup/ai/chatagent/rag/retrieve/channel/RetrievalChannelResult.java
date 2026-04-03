package org.javaup.ai.chatagent.rag.retrieve.channel;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 单个检索通道的结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalChannelResult {

    /**
     * 通道名称。
     */
    private String channelName;

    /**
     * 当前通道返回的候选文档。
     */
    private List<Document> documents;
}
