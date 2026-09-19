package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.javaup.ai.chatagent.model.SearchReference;
import org.springframework.ai.document.Document;

import java.util.List;


@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubQuestionEvidence {
                                    //含义     传入值
    private int subQuestionIndex;//子问题序号  当前子问题的索引

    private String subQuestion;//子问题文本   当前子问题内容

    private List<Document> documents;//最终进入 Prompt 的证据列表   finalDocuments（TopK 裁剪后的）

    private List<SearchReference> references;//搜索引用列表   new ArrayList<>()（当前为空，后续流程填充）

    private List<SubQuestionChannelTrace> channelTraces;//各通道的召回/采纳统计  channelTraces（buildChannelTraces 构建的）

    private Integer fusedCandidateCount;//RRF 融合后的候选数量   mergedCandidates.size()

    private Integer parentCandidateCount;//父块提升后的候选数量  parentCandidates.size()

    private Integer rerankedCandidateCount;//Rerank 后的候选数量  rerankedCandidates.size()
}
