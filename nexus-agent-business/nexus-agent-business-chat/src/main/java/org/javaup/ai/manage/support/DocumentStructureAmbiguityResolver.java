package org.javaup.ai.manage.support;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.config.DocumentManageProperties;
import org.javaup.ai.prompt.PromptTemplateNames;
import org.javaup.ai.prompt.PromptTemplateService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Slf4j
@Component
public class DocumentStructureAmbiguityResolver {

    private final DocumentManageProperties properties;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ObjectMapper objectMapper;
    private final PromptTemplateService promptTemplateService;

    public DocumentStructureAmbiguityResolver(DocumentManageProperties properties,
                                              ObjectProvider<ChatModel> chatModelProvider,
                                              ObjectMapper objectMapper,
                                              PromptTemplateService promptTemplateService) {
        this.properties = properties;
        this.chatModelProvider = chatModelProvider;
        this.objectMapper = objectMapper;
        this.promptTemplateService = promptTemplateService;
    }


    /*
    第二阶段：歧义消解
    第一阶段产出的信号里，最容易出错的就是那些 HEADING_CANDIDATE——它们置信度低，到底是标题还是列表项，规则很难 100% 判准。这时候就请 LLM 来帮忙做二次判定
    歧义消解的设计约束：
    这个阶段有意做了很强的约束：
只在配置开启 + 模型可用时才生效，否则直接跳过
只处理置信度落在指定区间内的模糊信号，不是所有信号都丢给 LLM
有数量上限，不会一次性发太多候选行
失败时静默回退到规则结果，不阻断主链
目的是让 LLM 只承担"判边界"的工作，而不是接管整套结构解析。
     */
    public List<DocumentStructureSignal> resolve(String documentTitle,
                                                 List<String> allLines,
                                                 List<DocumentStructureSignal> sourceSignals) {
        if (sourceSignals == null || sourceSignals.isEmpty()) {
            return List.of();
        }
        // 只在配置开启且模型可用时生效
        if (!Boolean.TRUE.equals(properties.getStructureParsing().getLlmDisambiguationEnabled())) {
            return sourceSignals;
        }
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            return sourceSignals;
        }

        // 只挑出真正模糊、值得交给 LLM 看一眼的信号
        List<DocumentStructureSignal> ambiguousSignals = sourceSignals.stream()
            .filter(signal -> signal != null
                && signal.isAmbiguous()
                && signal.getConfidence() >= properties.getStructureParsing().getAmbiguityConfidenceFloor()
                && signal.getConfidence() <= properties.getStructureParsing().getAmbiguityConfidenceCeil())
            .limit(Math.max(1, properties.getStructureParsing().getMaxAmbiguousSignalsPerCall()))
            .toList();
        if (ambiguousSignals.isEmpty()) {
            return sourceSignals;
        }

        try {
            // prompt 中只放局部上下文窗口，避免模型被整篇文档无关内容干扰
            String prompt = buildPrompt(documentTitle, ambiguousSignals, allLines);//buildPrompt：构造判歧pStringTemplate 模板文件中，通过 PromptTemplateService 统一渲染。先看两个模板文件，再看 Java 代码怎么调用它们。rompt 这里把提示词抽到外部的
            String content = ChatClient.builder(chatModel)
                .build()
                .prompt()
                .user(prompt)
                .call()
                .content();
            List<DisambiguationResult> results = parse(content);
            // ... 将 LLM 结果合并回原始信号列表 ...
            if (results.isEmpty()) {
                return sourceSignals;
            }
            Map<Integer, DisambiguationResult> resultMap = new LinkedHashMap<>();
            for (DisambiguationResult result : results) {
                if (result.lineNo == null) {
                    continue;
                }
                resultMap.put(result.lineNo, result);
            }
            List<DocumentStructureSignal> merged = new ArrayList<>(sourceSignals.size());
            for (DocumentStructureSignal signal : sourceSignals) {
                DisambiguationResult resolved = signal == null ? null : resultMap.get(signal.getLineNo());
                merged.add(applyResult(signal, resolved));
            }
            return merged;
        }
        catch (Exception exception) {
            // 判歧失败时只回退，不阻断主链
            log.warn("结构歧义判定失败，回退到规则结果: {}", exception.getMessage());
            return sourceSignals;
        }
    }


    //java侧的调用代码  buildPrompt 本身变得非常简洁——只负责把文档标题和候选行文本块传给主模板：
    /**
     * 构造发给 LLM 的判歧 prompt。
     * <p>
     * 通过 PromptTemplateService 渲染外部 .st 模板文件，
     * 而不是在代码里硬编码提示词文本。这样修改提示词只需要改模板文件，
     * 不用动 Java 代码，也不用重新编译。
     * </p>
     *
     * @param documentTitle    文档标题，会填充到模板的 documentTitle 占位符
     * @param ambiguousSignals 需要 LLM 判歧的低置信度信号列表
     * @param allLines         文档所有行的规范化文本，用于截取候选行的上下文窗口
     * @return 渲染后的完整 prompt 文本
     */
    private String buildPrompt(String documentTitle,
                               List<DocumentStructureSignal> ambiguousSignals,
                               List<String> allLines) {
        // 调用 PromptTemplateService 渲染主模板 document-structure-ambiguity.st，
        // 传入两个变量：documentTitle（文档标题）和 candidateBlocks（所有候选行拼接后的文本块）
        return promptTemplateService.render(PromptTemplateNames.DOCUMENT_STRUCTURE_AMBIGUITY, Map.of(
            "documentTitle", StrUtil.blankToDefault(documentTitle, "未命名文档"),
            "candidateBlocks", buildCandidateBlocks(ambiguousSignals, allLines)
        ));
    }


    /**
     * 候选行文本块的构建逻辑被拆到了buildCandidateBlocks 方法中：
     * 为每个低置信度候选行构建带上下文窗口的文本块。
     * <p>
     * 每个候选行会截取前后 N 行作为局部上下文（N 由配置项 contextWindowLines 控制），
     * 然后用 document-structure-ambiguity-candidate.st 模板渲染成一段文本。
     * 最终所有候选行的文本块拼接在一起，填充到主模板的 candidateBlocks 占位符中。
     * </p>
     *
     * @param ambiguousSignals 需要判歧的候选信号列表
     * @param allLines         文档所有行的规范化文本
     * @return 所有候选行文本块拼接后的字符串
     */
    /*
    prompt 的设计思路没有变：只给 LLM 看候选行周围的局部上下文窗口，不是整篇文档，既减少 token 消耗，又避免模型被无关内容干扰。变化在于实现方式——提示词文本从 Java 代码中剥离到了 .st 模板文件，通过 PromptTemplateService 统一加载和渲染。这样做的好处是：
1.修改提示词不用改代码：调整措辞、增删规则只需要编辑 .st 文件，不用重新编译部署
2.提示词和业务逻辑分离：Java 代码只关心"传什么变量"，模板文件只关心"怎么组织 prompt"
3.统一管理：所有提示词模板都放在 resources/prompt/ 目录下，通过 PromptTemplateNames 常量类引用，方便查找和维护
     */
    private String buildCandidateBlocks(List<DocumentStructureSignal> ambiguousSignals,
                                        List<String> allLines) {
        StringBuilder builder = new StringBuilder();
        // 防御性处理：allLines 为 null 时用空列表兜底
        List<String> safeLines = allLines == null ? List.of() : allLines;
        // 从配置中读取上下文窗口大小（候选行前后各取多少行），最小为 1
        int contextWindow = Math.max(1, properties.getStructureParsing().getContextWindowLines());
        for (DocumentStructureSignal signal : ambiguousSignals) {
            if (signal == null) {
                continue;
            }
            // 计算当前候选行在 allLines 中的索引（行号从 1 开始，索引从 0 开始）
            int currentIndex = Math.max(0, signal.getLineNo() - 1);
            // 计算上下文窗口的起止索引，确保不越界
            int start = Math.max(0, currentIndex - contextWindow);
            int end = Math.min(safeLines.size() - 1, currentIndex + contextWindow);
            // 构建上下文行文本：候选行本身用 ">> " 前缀高亮，其余行用 "   " 缩进对齐
            StringBuilder contextBuilder = new StringBuilder();
            for (int index = start; index <= end; index++) {
                contextBuilder.append(index + 1 == signal.getLineNo() ? ">> " : "   ")
                    .append(index + 1)
                    .append(": ")
                    .append(StrUtil.blankToDefault(safeLines.get(index), ""))
                    .append('\n');
            }
            // 用候选行模板渲染单个候选行的文本块，填入行号、上下文、初始分类等变量
            builder.append(promptTemplateService.render(PromptTemplateNames.DOCUMENT_STRUCTURE_AMBIGUITY_CANDIDATE, Map.of(
                "lineNo", signal.getLineNo(),
                "contextLines", contextBuilder.toString().stripTrailing(),
                "initialKind", signal.getKind() == null ? "" : signal.getKind().name(),
                "initialTitle", StrUtil.blankToDefault(signal.getTitle(), ""),
                "initialCode", StrUtil.blankToDefault(signal.getNodeCode(), "")
            ))).append("\n\n");
        }
        return builder.toString().trim();
    }

    private List<DisambiguationResult> parse(String raw) throws Exception {
        if (StrUtil.isBlank(raw)) {
            return List.of();
        }
        String normalized = raw.trim();
        int start = normalized.indexOf('[');
        int end = normalized.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return List.of();
        }
        String jsonArray = normalized.substring(start, end + 1);
        List<Map<String, Object>> items = objectMapper.readValue(jsonArray, new TypeReference<List<Map<String, Object>>>() {
        });
        List<DisambiguationResult> results = new ArrayList<>();
        for (Map<String, Object> item : items) {
            if (item == null) {
                continue;
            }
            Integer lineNo = item.get("line_no") instanceof Number number ? number.intValue() : null;
            String resolvedKind = item.get("resolved_kind") == null ? "" : String.valueOf(item.get("resolved_kind")).trim();
            Integer levelHint = item.get("level_hint") instanceof Number number ? number.intValue() : null;
            results.add(new DisambiguationResult(lineNo, resolvedKind, levelHint));
        }
        return results;
    }

    private DocumentStructureSignal applyResult(DocumentStructureSignal source,
                                                DisambiguationResult resolved) {
        if (source == null || resolved == null || StrUtil.isBlank(resolved.resolvedKind)) {
            return source;
        }
        DocumentStructureSignalKind targetKind = switch (resolved.resolvedKind.trim().toUpperCase()) {
            case "HEADING" -> DocumentStructureSignalKind.HEADING;
            case "LIST_ITEM" -> DocumentStructureSignalKind.LIST_ITEM;
            default -> DocumentStructureSignalKind.BODY;
        };
        source.setKind(targetKind);
        if (targetKind == DocumentStructureSignalKind.HEADING && resolved.levelHint != null && resolved.levelHint > 0) {
            source.setLevelHint(resolved.levelHint);
        }
        source.getReasons().add("llm-disambiguated");
        source.setConfidence(Math.max(source.getConfidence(), 0.88D));
        return source;
    }

    private record DisambiguationResult(
        Integer lineNo,
        String resolvedKind,
        Integer levelHint
    ) {
    }
}
