package org.javaup.ai.manage.service.impl;

import lombok.AllArgsConstructor;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.javaup.ai.manage.data.SuperAgentDocumentStructureNode;
import org.javaup.ai.manage.mapper.SuperAgentDocumentStructureNodeMapper;
import org.javaup.ai.manage.service.DocumentStructureNodeService;
import org.javaup.ai.manage.support.DocumentStructureNodeCandidate;
import org.javaup.enums.BusinessStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务实现层
 * @author: 阿星不是程序员
 **/

@AllArgsConstructor
@Service
public class DocumentStructureNodeServiceImpl implements DocumentStructureNodeService {

    private final SuperAgentDocumentStructureNodeMapper structureNodeMapper;
    private final UidGenerator uidGenerator;




    /*
    用最新候选节点整体替换文档结构节点
    <p>
 * 这个方法常用于“文档重新解析完成之后”，其目标不是增量更新，而是整棵树重建。
 * 因此它采用的是“先删旧数据，再插新数据”的整体替换策略。
 * </p>
 * <p>
 * 执行过程可以拆成三步：
 * 1. 删除当前文档已有的结构节点；
 * 2. 先为所有 candidate 的 {@code nodeNo} 预生成数据库主键 ID；
 * 3. 再第二轮遍历 candidate，把 parentNodeNo / prevSiblingNodeNo / nextSiblingNodeNo
 *    从“逻辑编号引用”翻译成“真实数据库 ID 引用”，最后逐条插入。
 * </p>
 * <p>
 * 之所以要分成两轮而不是一边遍历一边插入，是因为节点之间存在前向/后向引用：
 * 某个节点在插入时，可能需要同时知道自己的父节点 ID、前兄弟 ID、后兄弟 ID，
 * 而这些 ID 只有在“所有 nodeNo 对应的真实主键都提前生成完”之后才能稳定回填。
 * </p>
 *
 * @param documentId 文档 ID，表示要替换哪篇文档的结构树
 * @param parseTaskId 解析任务 ID，用于标识这批结构节点来自哪一次解析
 * @param candidates 结构提取链输出的候选节点列表
 * @return 最终落库成功的结构节点实体列表

     */
    @Override
    public List<SuperAgentDocumentStructureNode> replaceDocumentNodes(Long documentId,
                                                                      Long parseTaskId,
                                                                      List<DocumentStructureNodeCandidate> candidates) {
        // 先删旧结构树，确保当前文档在数据库里只保留“本次解析结果”这一份结构节点。
        deleteByDocumentId(documentId);
        // 任一关键参数缺失时，直接返回空列表；
        // 这意味着“结构树已清空，但没有新的节点需要写入”。
        if (documentId == null || parseTaskId == null || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        // nodeIdMap 的作用是把候选节点里的逻辑编号 nodeNo，预先映射成数据库真实主键。
        Map<Integer, Long> nodeIdMap = new LinkedHashMap<>();
        List<SuperAgentDocumentStructureNode> entities = new ArrayList<>();
        for (DocumentStructureNodeCandidate candidate : candidates) {
            if (candidate == null || candidate.getNodeNo() == null) {
                continue;
            }
            // 第一轮只做一件事：为每个逻辑节点号分配一个稳定的数据库 ID。
            long id = uidGenerator.getUid();
            nodeIdMap.put(candidate.getNodeNo(), id);
        }
        for (DocumentStructureNodeCandidate candidate : candidates) {
            if (candidate == null || candidate.getNodeNo() == null) {
                continue;
            }
            SuperAgentDocumentStructureNode entity = new SuperAgentDocumentStructureNode();
            // 节点自己的主键直接取第一轮预生成的 ID，确保后续引用关系可闭合。
            entity.setId(nodeIdMap.get(candidate.getNodeNo()));
            entity.setDocumentId(documentId);
            entity.setParseTaskId(parseTaskId);
            entity.setNodeNo(candidate.getNodeNo());
            entity.setNodeType(candidate.getNodeType());
            // 这里最关键：candidate 中的 parent/prev/next 保存的是逻辑 nodeNo，
            // 入库前必须统一翻译成数据库真实主键 ID。
            entity.setParentNodeId(candidate.getParentNodeNo() == null ? null : nodeIdMap.get(candidate.getParentNodeNo()));
            entity.setPrevSiblingNodeId(candidate.getPrevSiblingNodeNo() == null ? null : nodeIdMap.get(candidate.getPrevSiblingNodeNo()));
            entity.setNextSiblingNodeId(candidate.getNextSiblingNodeNo() == null ? null : nodeIdMap.get(candidate.getNextSiblingNodeNo()));
            entity.setDepth(candidate.getDepth());
            entity.setNodeCode(candidate.getNodeCode());
            entity.setTitle(candidate.getTitle());
            entity.setAnchorText(candidate.getAnchorText());
            entity.setCanonicalPath(candidate.getCanonicalPath());
            entity.setSectionPath(candidate.getSectionPath());
            entity.setContentText(candidate.getContentText());
            entity.setItemIndex(candidate.getItemIndex());
            entity.setStatus(BusinessStatus.YES.getCode());
            // 每个节点逐条插入，便于保留完整的树节点信息。
            structureNodeMapper.insert(entity);
            entities.add(entity);
        }
        return entities;
    }

    @Override
    public List<SuperAgentDocumentStructureNode> listDocumentNodes(Long documentId, Long parseTaskId) {
        if (documentId == null) {
            return List.of();
        }
        LambdaQueryWrapper<SuperAgentDocumentStructureNode> wrapper = new LambdaQueryWrapper<SuperAgentDocumentStructureNode>()
            .eq(SuperAgentDocumentStructureNode::getDocumentId, documentId)
            .eq(SuperAgentDocumentStructureNode::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentDocumentStructureNode::getNodeNo);
        if (parseTaskId != null) {
            wrapper.eq(SuperAgentDocumentStructureNode::getParseTaskId, parseTaskId);
        }
        return structureNodeMapper.selectList(wrapper);
    }

    @Override
    public Map<Long, SuperAgentDocumentStructureNode> nodeMap(Long documentId, Long parseTaskId) {
        Map<Long, SuperAgentDocumentStructureNode> result = new LinkedHashMap<>();
        for (SuperAgentDocumentStructureNode node : listDocumentNodes(documentId, parseTaskId)) {
            result.put(node.getId(), node);
        }
        return result;
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        if (documentId == null) {
            return;
        }
        structureNodeMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentStructureNode>()
            .eq(SuperAgentDocumentStructureNode::getDocumentId, documentId));
    }
}
