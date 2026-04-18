package org.javaup.ai.chatagent.rag.retrieve.channel;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 结果对象
 * @author: 阿星不是程序员
 **/
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
