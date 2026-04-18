package org.javaup.ai.manage.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 支撑组件
 * @author: 阿星不是程序员
 **/
/**
 * 父块候选对象。
 *
 * <p>这是 Parent-Child / Small-to-Big 结构里的“Big”层。
 * 它代表的是后续回答阶段真正应该喂给模型的较大语义单元，
 * 而不是直接拿来做向量召回的小块。</p>
 *
 * <p>当前教学版里它承担两个职责：</p>
 * <p>1. 在索引构建阶段，把稳定的大块边界保存下来。</p>
 * <p>2. 在索引落库前，显式挂上一组从父块内部派生出来的 child chunk。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParentBlockCandidate {

    /**
     * 父块对应的章节路径。
     */
    private String sectionPath;

    /**
     * 关联的结构节点 id。
     */
    private Long structureNodeId;

    /**
     * 关联的结构节点类型。
     */
    private Integer structureNodeType;

    /**
     * 结构节点稳定路径。
     */
    private String canonicalPath;

    /**
     * 列表/步骤项序号。
     */
    private Integer itemIndex;

    /**
     * 父块完整正文。
     */
    private String text;

    /**
     * 内容来源。
     */
    private Integer sourceType;

    /**
     * 从当前父块内部继续派生出来的 child chunk。
     */
    private List<ChunkCandidate> childChunks = new ArrayList<>();

    public ParentBlockCandidate(String sectionPath,
                                String text,
                                Integer sourceType,
                                List<ChunkCandidate> childChunks) {
        this(sectionPath, null, null, "", null, text, sourceType, childChunks);
    }
}
