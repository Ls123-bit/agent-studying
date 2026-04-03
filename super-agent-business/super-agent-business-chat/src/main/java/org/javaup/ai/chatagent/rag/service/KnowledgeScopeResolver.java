package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.KnowledgeScopeOption;
import org.javaup.ai.chatagent.rag.model.KnowledgeScopeResolution;
import org.javaup.ai.manage.model.KnowledgeDocumentDescriptor;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 知识域解析服务。
 *
 * <p>当前实现不做完整的意图树和节点配置中心，
 * 而是走一个更轻量、也更贴合当前项目的方案：
 * 直接基于文档主表上的知识域字段，对问题做命中和收缩。</p>
 */
@Service
public class KnowledgeScopeResolver {

    private static final List<String> AMBIGUOUS_HINTS = List.of(
        "系统", "流程", "这个", "那个", "入口", "在哪", "怎么走", "怎么用", "怎么配", "支持吗"
    );

    private final DocumentKnowledgeService documentKnowledgeService;
    private final ChatRagProperties properties;

    public KnowledgeScopeResolver(DocumentKnowledgeService documentKnowledgeService,
                                  ChatRagProperties properties) {
        this.documentKnowledgeService = documentKnowledgeService;
        this.properties = properties;
    }

    /**
     * 解析当前问题应该落到哪些知识域上。
     */
    public KnowledgeScopeResolution resolve(String rewrittenQuestion, String historySummary) {
        /*
         * 先取出当前系统里所有“索引可用”的文档目录。
         * 没有目录就没法做知识域收缩，也没必要继续往后算。
         */
        List<KnowledgeDocumentDescriptor> descriptors = documentKnowledgeService.listRetrievableDocuments();
        if (CollUtil.isEmpty(descriptors)) {
            return KnowledgeScopeResolution.builder().build();
        }

        /*
         * 先按 knowledge scope 聚成候选项。
         * 这一步把“文档粒度”提升成“知识域粒度”，后面澄清和收缩都围绕这个层级进行。
         */
        List<KnowledgeScopeOption> options = groupScopes(descriptors);
        if (options.size() <= 1) {
            /*
             * 如果系统里天然只有一个知识域，或者当前有效文档最终只聚成了一个候选，
             * 那就不需要再做歧义判断，直接把它选为检索范围。
             */
            return selectOptions(options);
        }

        /*
         * 有多个知识域候选时，先根据“改写后的问题 + 历史摘要”做轻量打分。
         * 只要命中明确，就直接收缩到高分候选，不再追问。
         */
        List<KnowledgeScopeOption> matchedOptions = scoreOptions(options, rewrittenQuestion, historySummary);
        if (!matchedOptions.isEmpty()) {
            return selectOptions(matchedOptions);
        }

        if (properties.isClarifyEnabled() && seemsAmbiguous(rewrittenQuestion)) {
            /*
             * 到这里说明：当前确实存在多个知识域候选，但问题本身又模糊，无法自动收缩。
             * 这种场景下，继续检索大概率会答偏，所以直接生成澄清提示更稳。
             */
            List<KnowledgeScopeOption> clarifyOptions = options.stream()
                .limit(properties.getClarifyMaxOptions())
                .toList();
            return KnowledgeScopeResolution.builder()
                .clarifyRequired(true)
                .clarifyPrompt(buildClarifyPrompt(clarifyOptions))
                .options(clarifyOptions)
                .build();
        }

        /*
         * 如果既没有明显命中，也不满足“必须澄清”的条件，
         * 当前策略是保守地把所有候选都纳入检索范围，后续再由检索结果自己做区分。
         */
        return selectOptions(options);
    }

    /**
     * 以知识域为单位聚合同一批文档。
     */
    private List<KnowledgeScopeOption> groupScopes(List<KnowledgeDocumentDescriptor> descriptors) {
        Map<String, KnowledgeScopeOption> grouped = new LinkedHashMap<>();
        for (KnowledgeDocumentDescriptor descriptor : descriptors) {
            /*
             * 文档没有填知识域时，也不能直接丢掉。
             * 这里会退回到一个“以文档自身为 scope”的兜底编码，保证每份文档仍然可参与检索。
             */
            String scopeCode = StrUtil.blankToDefault(StrUtil.trim(descriptor.getKnowledgeScopeCode()), "DOC-" + descriptor.getDocumentId());
            String scopeName = StrUtil.blankToDefault(StrUtil.trim(descriptor.getKnowledgeScopeName()), descriptor.getDocumentName());
            KnowledgeScopeOption option = grouped.computeIfAbsent(scopeCode, key -> new KnowledgeScopeOption(
                scopeCode,
                scopeName,
                new ArrayList<>(),
                new ArrayList<>(),
                0D,
                new ArrayList<>()
            ));
            option.getDocumentIds().add(descriptor.getDocumentId());
            option.getTaskIds().add(descriptor.getLastIndexTaskId());
            option.getDocumentNames().add(descriptor.getDocumentName());
        }
        return new ArrayList<>(grouped.values());
    }

    /**
     * 根据问题内容给候选知识域打分。
     */
    private List<KnowledgeScopeOption> scoreOptions(List<KnowledgeScopeOption> options,
                                                    String rewrittenQuestion,
                                                    String historySummary) {
        /*
         * 这里把改写问题和历史摘要拼成一个“统一命中文本”。
         * 目的是让 scopeName、scopeCode、documentName 的匹配都基于同一份上下文来做。
         */
        String mergedContext = normalize(rewrittenQuestion + " " + StrUtil.blankToDefault(historySummary, ""));
        List<KnowledgeScopeOption> matched = new ArrayList<>();
        for (KnowledgeScopeOption option : options) {
            double score = scoreOption(option, mergedContext);
            if (score <= 0D) {
                continue;
            }
            matched.add(new KnowledgeScopeOption(
                option.getScopeCode(),
                option.getScopeName(),
                option.getDocumentIds(),
                option.getTaskIds(),
                score,
                option.getDocumentNames()
            ));
        }
        matched.sort((left, right) -> Double.compare(right.getScore(), left.getScore()));
        return matched;
    }

    private double scoreOption(KnowledgeScopeOption option, String mergedContext) {
        double score = 0D;
        /*
         * 当前打分是一个非常轻量的启发式模型：
         * - scopeName 权重最高，因为用户通常更容易直接提系统名
         * - scopeCode 次之，适合命中英文编码或简称
         * - documentName 权重最低，只作为补充命中
         */
        score += matchScore(mergedContext, option.getScopeName(), 8D);
        score += matchScore(mergedContext, option.getScopeCode(), 6D);
        for (String documentName : option.getDocumentNames()) {
            score += matchScore(mergedContext, documentName, 3D);
        }
        return score;
    }

    private double matchScore(String mergedContext, String candidate, double weight) {
        if (StrUtil.isBlank(candidate)) {
            return 0D;
        }
        /*
         * 命中文本和候选项都会先做“去标点 + 去空白 + 小写”的归一化，
         * 这样中文系统名、英文编码和混合表达都能用统一逻辑做 contains 判断。
         */
        String normalizedCandidate = normalize(candidate);
        if (normalizedCandidate.length() < 2) {
            return 0D;
        }
        return mergedContext.contains(normalizedCandidate) ? weight : 0D;
    }

    /**
     * 是否看起来像一个需要补充系统名的模糊问题。
     */
    private boolean seemsAmbiguous(String question) {
        if (StrUtil.isBlank(question)) {
            return true;
        }
        /*
         * 问题太短时通常缺少足够的业务指向信息，
         * 当前策略直接认为它需要用户补充上下文。
         */
        if (question.trim().length() <= 8) {
            return true;
        }
        /*
         * 这些词本身不代表具体系统，只代表一种模糊诉求。
         * 一旦命中，就优先认为问题可能需要澄清。
         */
        return AMBIGUOUS_HINTS.stream().anyMatch(question::contains);
    }

    /**
     * 构造澄清追问。
     */
    private String buildClarifyPrompt(List<KnowledgeScopeOption> options) {
        /*
         * 这里故意把候选项做成明确的序号列表，而不是一段自然语言描述。
         * 对用户来说更容易直接回复“第一个”或直接说出系统名。
         */
        StringBuilder prompt = new StringBuilder("我需要先确认你想问的是哪个业务系统，请在下面这些知识域里选一个：\n");
        for (int index = 0; index < options.size(); index++) {
            prompt.append(index + 1)
                .append(". ")
                .append(options.get(index).getScopeName())
                .append("\n");
        }
        prompt.append("\n你也可以直接补充更具体的系统名称、模块名称或业务关键词。");
        return prompt.toString().trim();
    }

    /**
     * 把命中的知识域候选项收束成真正的检索范围。
     */
    private KnowledgeScopeResolution selectOptions(List<KnowledgeScopeOption> options) {
        if (CollUtil.isEmpty(options)) {
            return KnowledgeScopeResolution.builder().build();
        }

        /*
         * 这里会把多个候选 knowledge scope 下的文档和 task 聚合成一组去重后的检索范围。
         * 后面的检索引擎不再理解“知识域候选”，只理解最终应该查哪些 documentId / taskId。
         */
        LinkedHashSet<Long> documentIds = new LinkedHashSet<>();
        LinkedHashSet<Long> taskIds = new LinkedHashSet<>();
        for (KnowledgeScopeOption option : options) {
            documentIds.addAll(option.getDocumentIds());
            taskIds.addAll(option.getTaskIds());
        }

        return KnowledgeScopeResolution.builder()
            .clarifyRequired(false)
            .options(options)
            .selectedDocumentIds(new ArrayList<>(documentIds))
            .selectedTaskIds(new ArrayList<>(taskIds))
            .build();
    }

    private String normalize(String text) {
        if (StrUtil.isBlank(text)) {
            return "";
        }
        /*
         * 这里采用最简单但很实用的归一化方式：
         * 去掉标点和空白后做 contains 匹配，避免系统名里带空格、括号、连字符导致命中失败。
         */
        return text.trim().toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s]+", "");
    }
}
