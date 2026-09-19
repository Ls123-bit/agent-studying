package org.javaup.ai.chatagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;


/*
这个视图对象记录了一个候选文档从召回到最终裁决的完整生命周期。按功能分组来看：
关联信息：id、traceId、subQuestionIndex、subQuestion —— 把这条记录关联到具体的对话和子问题
排名信息：channelRank（通道内排名）、rrfRank（RRF 融合后排名）、finalRank（最终排名）—— 追踪排名变化
分数信息：originalScore（原始分）、rrfScore（融合分）、rerankScore（精排分）—— 追踪分数变化
状态信息：gatePassed（是否过闸门）、isElevated（是否被父块提升）、isSelected（是否入选）、selectionReason（原因说明）
来源信息：documentId、documentName、chunkId、chunkNo、sectionPath —— 定位证据出处
内容信息：chunkTextPreview（文本预览）、chunkCharCount（完整字符数）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalResultView {

    private Long id;
    private String traceId;
    private int subQuestionIndex;
    private String subQuestion;
    private String channelType;
    private Integer channelRank;
    private Integer rrfRank;
    private Integer finalRank;
    private BigDecimal originalScore;
    private BigDecimal rrfScore;
    private BigDecimal rerankScore;
    private boolean gatePassed;
    private boolean isElevated;
    private boolean isSelected;
    private String selectionReason;
    private Long documentId;
    private String documentName;
    private Long chunkId;
    private Integer chunkNo;
    private Long parentBlockId;
    private Integer parentBlockNo;
    private String sectionPath;
    private String chunkTextPreview;
    private Integer chunkCharCount;
    private Instant createTime;
}
