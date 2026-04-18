package org.javaup.ai.manage.support;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 支撑组件
 * @author: 阿星不是程序员
 **/
/**
 * 文档结构节点提取器。
 *
 * <p>当前版本采用“四段式结构解析”：</p>
 * <p>1. 代码先抽取行级结构信号</p>
 * <p>2. 仅对低置信度行使用 LLM 判歧</p>
 * <p>3. 代码根据编号体系、Markdown 层级和上下文关系生成草稿树</p>
 * <p>4. 最后统一做结构校验与修复</p>
 *
 * <p>这样既能利用规则解析的一致性和低成本，
 * 又能在标题/列表/正文边界模糊时借助模型补充语义判断。</p>
 */
@Component
public class DocumentStructureNodeExtractor {

    private final DocumentStructureSignalExtractor signalExtractor;
    private final DocumentStructureAmbiguityResolver ambiguityResolver;
    private final DocumentStructureHierarchyResolver hierarchyResolver;
    private final DocumentStructureTreeValidator treeValidator;

    public DocumentStructureNodeExtractor(DocumentStructureSignalExtractor signalExtractor,
                                          DocumentStructureAmbiguityResolver ambiguityResolver,
                                          DocumentStructureHierarchyResolver hierarchyResolver,
                                          DocumentStructureTreeValidator treeValidator) {
        this.signalExtractor = signalExtractor;
        this.ambiguityResolver = ambiguityResolver;
        this.hierarchyResolver = hierarchyResolver;
        this.treeValidator = treeValidator;
    }

    /**
     * 从清洗后的正文中提取结构节点列表。
     */
    public List<DocumentStructureNodeCandidate> extract(String documentTitle, String parsedText) {
        String normalizedTitle = StrUtil.blankToDefault(documentTitle, "文档").trim();
        String normalizedText = StrUtil.blankToDefault(parsedText, "").trim();
        if (normalizedText.isBlank()) {
            return List.of(new DocumentStructureNodeCandidate(
                1,
                org.javaup.enums.DocumentStructureNodeTypeEnum.DOCUMENT.getCode(),
                null,
                0,
                0,
                0,
                "",
                normalizedTitle,
                normalizedTitle,
                "/document",
                "",
                "",
                null
            ));
        }

        DocumentStructureSignalBatch signalBatch = signalExtractor.extract(normalizedTitle, normalizedText);
        List<DocumentStructureSignal> rawSignals = signalBatch == null ? List.of() : signalBatch.signals();
        List<String> allLines = signalBatch == null ? List.of() : signalBatch.contextLines();
        List<DocumentStructureSignal> resolvedSignals = ambiguityResolver.resolve(normalizedTitle, allLines, rawSignals);
        List<DocumentStructureNodeDraft> drafts = hierarchyResolver.resolve(normalizedTitle, resolvedSignals);
        return treeValidator.validateAndBuild(normalizedTitle, drafts);
    }
}
