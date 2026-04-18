package org.javaup.ai.manage.support;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.config.DocumentManageProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 支撑组件
 * @author: 阿星不是程序员
 **/
/**
 * 结构歧义判定器。
 *
 * <p>它只处理“代码难以稳定判断”的少量低置信度行，
 * 不接管全文结构树构建。</p>
 */
@Slf4j
@Component
public class DocumentStructureAmbiguityResolver {

    private final DocumentManageProperties properties;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ObjectMapper objectMapper;

    public DocumentStructureAmbiguityResolver(DocumentManageProperties properties,
                                              ObjectProvider<ChatModel> chatModelProvider,
                                              ObjectMapper objectMapper) {
        this.properties = properties;
        this.chatModelProvider = chatModelProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * 用 LLM 修正低置信度结构信号。
     */
    public List<DocumentStructureSignal> resolve(String documentTitle,
                                                 List<String> allLines,
                                                 List<DocumentStructureSignal> sourceSignals) {
        if (sourceSignals == null || sourceSignals.isEmpty()) {
            return List.of();
        }
        if (!Boolean.TRUE.equals(properties.getStructureParsing().getLlmDisambiguationEnabled())) {
            return sourceSignals;
        }
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            return sourceSignals;
        }

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
            String prompt = buildPrompt(documentTitle, ambiguousSignals, allLines);
            String content = ChatClient.builder(chatModel)
                .build()
                .prompt()
                .user(prompt)
                .call()
                .content();
            List<DisambiguationResult> results = parse(content);
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
            log.warn("结构歧义判定失败，回退到规则结果: {}", exception.getMessage());
            return sourceSignals;
        }
    }

    private String buildPrompt(String documentTitle,
                               List<DocumentStructureSignal> ambiguousSignals,
                               List<String> allLines) {
        StringBuilder builder = new StringBuilder("""
            你是文档结构判歧助手。
            你的任务是判断若干低置信度文本行，在当前上下文中更像：
            - HEADING：章节/小节标题
            - LIST_ITEM：普通列表项
            - BODY：普通正文

            请严格返回 JSON 数组，不要附加解释：
            [
              {
                "line_no": 12,
                "resolved_kind": "HEADING | LIST_ITEM | BODY",
                "level_hint": 1
              }
            ]

            规则：
            1. 只有在非常像章节标题时才输出 HEADING。
            2. 连续出现的编号项、步骤项、清单项优先判断为 LIST_ITEM。
            3. 表格说明行、引用行、解释性句子优先判断为 BODY。
            4. level_hint 只有 resolved_kind=HEADING 时才填写；没有把握时填 null。
            5. 不要脑补目录结构，只依据提供的局部上下文判断。

            文档标题：
            """).append(StrUtil.blankToDefault(documentTitle, "未命名文档")).append("\n\n");

        int contextWindow = Math.max(1, properties.getStructureParsing().getContextWindowLines());
        for (DocumentStructureSignal signal : ambiguousSignals) {
            builder.append("### 候选行 ").append(signal.getLineNo()).append('\n');
            int currentIndex = Math.max(0, signal.getLineNo() - 1);
            int start = Math.max(0, currentIndex - contextWindow);
            int end = Math.min(allLines.size() - 1, currentIndex + contextWindow);
            for (int index = start; index <= end; index++) {
                builder.append(index + 1 == signal.getLineNo() ? ">> " : "   ")
                    .append(index + 1)
                    .append(": ")
                    .append(StrUtil.blankToDefault(allLines.get(index), ""))
                    .append('\n');
            }
            builder.append("初始判断：").append(signal.getKind()).append('\n');
            builder.append("初始标题：").append(StrUtil.blankToDefault(signal.getTitle(), "")).append('\n');
            builder.append("初始编码：").append(StrUtil.blankToDefault(signal.getNodeCode(), "")).append("\n\n");
        }
        return builder.toString();
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
