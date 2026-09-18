package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.RagRewriteResult;
import org.javaup.ai.chatagent.service.ConversationTraceRecorder;
import org.javaup.ai.chatagent.service.ObservedChatModelService;
import org.javaup.ai.prompt.PromptTemplateNames;
import org.javaup.ai.prompt.PromptTemplateService;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务层
 * @author: 阿星不是程序员
 **/


/*
问题改写
把用户的原始问题改写成更适合检索的形式，必要时拆成多个子问题。
 */
@Slf4j
@Service
public class ChatQueryRewriteService {

    private static final Pattern NUMBERED_MULTI_QUESTION_PATTERN = Pattern.compile("(^|\\s)(\\d+[)\\.、]|[A-Za-z][)])");
    private static final Pattern MULTI_LINE_PATTERN = Pattern.compile("\\n+");

    private final ObservedChatModelService observedChatModelService;
    private final ObjectMapper objectMapper;
    private final ChatRagProperties properties;
    private final PromptTemplateService promptTemplateService;

    public ChatQueryRewriteService(ObservedChatModelService observedChatModelService,
                                   ObjectMapper objectMapper,
                                   ChatRagProperties properties,
                                   PromptTemplateService promptTemplateService) {
        this.observedChatModelService = observedChatModelService;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.promptTemplateService = promptTemplateService;
    }

    public RagRewriteResult rewrite(String question, String historySummary) {//简化版入口
        return rewrite(question, historySummary, null);
    }


    /*
    是整个改写流程的入口
    执行问题改写主流程。
 * 这里会先判断是否值得改写，若不值得则直接走规则兜底；
 * 若需要改写，则调用模型生成 JSON 结构化结果，并在失败时退回规则改写。
 * @param question 原始问题
 * @param historySummary 历史摘要
 * @param traceRecorder 模型调用追踪器
 * @return 改写结果

     */
    public RagRewriteResult rewrite(String question,
                                    String historySummary,
                                    ConversationTraceRecorder traceRecorder) {//完整版，带链路追踪
       /*
       第一步：空值检查
       如果问题为空或只有空格，直接返回空结果，没必要继续处理。
        */
        String normalizedQuestion = StrUtil.trim(question);
        if (StrUtil.isBlank(normalizedQuestion)) {
            // 空问题没有改写意义，直接返回空结果。
            return new RagRewriteResult("", List.of());
        }
        /*
        第二步：判断是否需要改写
        这里有两个判断条件：
        1.配置开关 - properties.isRewriteEnabled() 是否为 true
        2.是否值得改写 - needsRewrite(normalizedQuestion, historySummary) 的判断结果
        __________________________________________________________________________
        如果任意一个条件不满足，就直接走规则兜底，不调用模型。
        为什么要判断"是否值得改写"？
        调用模型是有成本的（时间成本和金钱成本）。如果问题本身就很清晰、很完整，没必要再让模型改写一遍。所以系统会先用规则判断一下，只有真正需要改写的问题才会调用模型。
         */
        if (!properties.isRewriteEnabled() || !needsRewrite(normalizedQuestion, historySummary)) {
            // 未开启改写或当前问题不值得改写时，直接走规则兜底。
            RagRewriteResult fallback = fallback(normalizedQuestion);
            log.info("RAG 改写跳过: question='{}', rewritten='{}', subQuestions={}",
                normalizedQuestion,
                fallback.getRewrittenQuestion(),
                fallback.getSubQuestions());
            return fallback;
        }
        try {

            /*第三步：调用模型进行改写
            这里做了两件事：
            1.渲染提示词 - 把历史摘要和当前问题填充到提示词模板里
            2.调用模型 - 通过 observedChatModelService.callText 调用大模型
            模型会返回一个 JSON 格式的字符串，包含改写后的问题和子问题列表。
            提示词模板的具体内容
            PromptTemplateNames.CHAT_QUERY_REWRITE 对应的模板文件是 chat-query-rewrite.st
             */
            // 使用提示词模板把历史摘要和当前问题渲染成结构化改写 Prompt。
            String prompt = promptTemplateService.render(PromptTemplateNames.CHAT_QUERY_REWRITE, Map.of(
                "history", StrUtil.isNotBlank(historySummary) ? historySummary : "无历史上下文",
                "question", normalizedQuestion
            ));
            // 调用模型生成改写结果，阶段名固定为 rewrite，便于追踪与成本统计。
            String raw = observedChatModelService.callText("rewrite", null, prompt, buildRewriteCallOptions(), traceRecorder);
            /*
            第四步：解析和归一化结果
            这里调用了两个方法：
            1.parse(raw) - 把模型返回的 JSON 字符串解析成 ParsedRewritePayload 对象
            2.normalizeRewriteResult - 对解析结果做归一化处理，比如去重、裁剪子问题数量等
            如果解析成功且改写结果不为空，就返回改写结果；否则继续往下走。
             */
            RagRewriteResult parsed = normalizeRewriteResult(normalizedQuestion, parse(raw));
            if (parsed != null && StrUtil.isNotBlank(parsed.getRewrittenQuestion())) {
                // 保留模型原始输出，便于后续调试"为什么改写成这样"。
                parsed.setRawModelOutput(raw);
                log.info("RAG 改写完成: question='{}', rewritten='{}', subQuestions={}",
                    normalizedQuestion,
                    parsed.getRewrittenQuestion(),
                    parsed.getSubQuestions());
                return parsed;
            }



            // 如果模型输出结构不合法或没有可用改写，则记录告警并退回规则改写。
            log.warn("RAG 改写结果不可用，回退到规则改写: question='{}', raw='{}'",
                normalizedQuestion,
                StrUtil.blankToDefault(raw, ""));
        }
        /*
        第五步：异常处理和规则兜底
        如果模型调用失败、解析失败、或者解析结果不可用，都会走到这里，调用 fallback 方法进行规则兜底。
        为什么要有规则兜底？
        模型调用可能会失败（网络问题、模型服务不可用等），模型输出也可能不符合预期格式。如果没有兜底机制，整个问答流程就会中断。有了规则兜底，即使模型改写失败，系统也能继续工作，只是改写质量可能会差一些。
         */
        catch (Exception exception) {
            // 模型调用或解析异常都不阻断主流程，而是回退到规则改写。
            log.warn("RAG 改写失败，回退到规则改写: question='{}', message={}",
                normalizedQuestion,
                exception.getMessage());
        }
        return fallback(normalizedQuestion);
    }


    /*
     * 构造改写阶段的模型调用参数。（构建模型调用参数）
     * @return 改写调用参数；若未开启覆写则返回 null
     * ————————————————————————————————————————
     * 这个方法用来构建改写阶段的模型调用参数。如果配置里没有开启参数覆写，就返回 null，使用默认参数。如果开启了，就根据配置构建自定义参数：
        temperature - 控制输出的随机性，越低越确定
        topP - 控制采样范围
        thinking - 是否开启思考模式（某些模型支持）
     */
    private ChatOptions buildRewriteCallOptions() {
        ChatRagProperties.RewriteOptionsProperties rewriteOptions = properties.getRewriteOptions();
        if (rewriteOptions == null || !rewriteOptions.isEnabled()) {
            log.info("RAG 改写模型参数: overrideEnabled=false, useDefaultModelOptions=true");
            return null;
        }
        log.info("RAG 改写模型参数: overrideEnabled=true, temperature={}, topP={}, thinking={}",
            rewriteOptions.getTemperature(),
            rewriteOptions.getTopP(),
            rewriteOptions.getThinking());
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
        if (rewriteOptions.getTemperature() != null) {
            builder.temperature(rewriteOptions.getTemperature());
        }
        if (rewriteOptions.getTopP() != null) {
            builder.topP(rewriteOptions.getTopP());
        }
        if (rewriteOptions.getThinking() != null) {
            // thinking 通过 extraBody 下发，适配支持该扩展参数的 OpenAI 兼容模型。
            builder.extraBody(Map.of("thinking", rewriteOptions.getThinking()));
        }
        return builder.build();
    }


    /*规则兜底改写  当模型改写失败或不需要改写时，会调用这个方法：
规则兜底改写。
 * 单问题直接原样返回；多问题则按规则拆分。
 * @param normalizedQuestion 归一化问题
 * @return 兜底改写结果
 ————————————————————————————————————————————————————————————————
 逻辑很简单：
单问题 - 直接返回原问题，子问题列表也只有一个元素
多问题 - 调用 ruleBasedSplit 进行规则拆分

     */
    private RagRewriteResult fallback(String normalizedQuestion) {//LLM 失败时的兜底方案
        if (!looksLikeExplicitMultiQuestion(normalizedQuestion)) {
            return new RagRewriteResult(normalizedQuestion, List.of(normalizedQuestion));
        }
        return new RagRewriteResult(normalizedQuestion, ruleBasedSplit(normalizedQuestion));
    }


    /*判断是否需要改写/断当前问题是否值得进入模型改写。
        param question 当前问题
         * @param historySummary 历史摘要
         * @return true 表示建议改写
     */
    private boolean needsRewrite(String question, String historySummary) {//判断是否需要 LLM 改写
        if (StrUtil.isBlank(historySummary)) {
            // 没有历史时，只有问题过短或明显是多问句时才值得改写。
            return question.length() < 8 || looksLikeExplicitMultiQuestion(question);
        }
        // 有历史时，适当放宽阈值，因为改写更容易从上下文中消解指代。
        return question.length() < 18 || looksLikeExplicitMultiQuestion(question);
    }


    /*识别多问句  这个方法用来判断问题是否像"显式多问句"：
    判断问题是否像"显式多问句"。
 * @param question 当前问题
 * @return true 表示更像多问句

     */
    private boolean looksLikeExplicitMultiQuestion(String question) {//检测多问题模式（多个问号、分号、编号列表等）
        String normalized = StrUtil.blankToDefault(question, "").trim();
        if (normalized.isBlank()) {
            return false;
        }
        long questionMarkCount = normalized.chars().filter(ch -> ch == '?' || ch == '？').count();
        if (questionMarkCount >= 2) {
            // 多个问号通常意味着用户一次提了多个问题。
            return true;
        }
        if (normalized.contains("；") || normalized.contains(";")) {
            // 分号常用于并列多个问题。
            return true;
        }
        if (MULTI_LINE_PATTERN.matcher(normalized).find()) {
            long nonBlankLineCount = Arrays.stream(normalized.split("\\n+"))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .count();
            if (nonBlankLineCount >= 2) {
                // 多行非空内容通常也是多问句信号。
                return true;
            }
        }
        if (NUMBERED_MULTI_QUESTION_PATTERN.matcher(normalized).find()) {
            // 编号列表结构往往意味着显式拆分多个问题。
            return true;
        }
        return normalized.contains("分别");
    }



    /*归一化改写结果   这个方法是改写流程中最复杂的一个，它负责对模型返回的结果做各种归一化处理：
里会处理 shouldSplit、子问题缺失、过多子问题裁剪等规则，保证输出适合后续检索链使用。
 * @param originalQuestion 原始问题
 * @param parsed 模型解析结果
 * @return 归一化后的改写结果
     */
    private RagRewriteResult normalizeRewriteResult(String originalQuestion,
                                                    ParsedRewritePayload parsed) {//标准化 LLM 输出，限制子问题数量
        if (parsed == null) {
            return null;
        }
        String rewrite = StrUtil.blankToDefault(parsed.rewrite(), originalQuestion).trim();
        if (StrUtil.isBlank(rewrite)) {
            return null;
        }
        // 第一步：清洗子问题列表 先对模型返回的子问题做清洗、去空和去重。 （对模型返回的子问题列表做三件事：1. 过滤空白 - 去掉空字符串 2.trim - 去掉首尾空格 3.去重 - 避免重复的子问题）
        List<String> subQuestions = parsed.subQuestions() == null
            ? new ArrayList<>()
            : parsed.subQuestions().stream()
                .filter(StrUtil::isNotBlank)
                .map(String::trim)
                .distinct()
                .toList();

        /*
        第二步：保守的拆分判断
        这里有个很重要的设计：双重确认机制。
只有同时满足两个条件，才会真正拆分子问题：
1.模型建议拆分 - shouldSplit == true
2.原问题结构像多问句 - explicitMultiQuestion == true
         */
        boolean explicitMultiQuestion = looksLikeExplicitMultiQuestion(originalQuestion);
        boolean shouldSplit = Boolean.TRUE.equals(parsed.shouldSplit());
        if (!shouldSplit || !explicitMultiQuestion) {
            // 只有在"模型建议拆分"且"原问题结构也确实像多问句"时才真正接受拆分。
            if (shouldSplit && !explicitMultiQuestion && subQuestions.size() > 1) {
                log.info("RAG 改写子问题收敛: question='{}', rewrite='{}', originalSubQuestionCount={}, reason='llm-split-rejected-by-conservative-structure-check'",
                    originalQuestion,
                    rewrite,
                    subQuestions.size());
            }
            subQuestions = List.of(rewrite);
        }
        /*
        第三步：兜底拆分
        如果模型建议拆分，但是没有给出有效的子问题列表（为空或全是空白），
        系统会调用 ruleBasedSplit 进行规则拆分。如果规则拆分也失败了（只拆出一个问题），就直接用改写后的问题作为唯一的子问题。
         */
        else if (subQuestions.isEmpty()) {
            // 模型说要拆分但没给出有效子问题时，退回规则拆分。
            List<String> fallbackSplit = ruleBasedSplit(originalQuestion);
            if (fallbackSplit.size() > 1) {
                subQuestions = fallbackSplit;
            }
            else {
                subQuestions = List.of(rewrite);
            }
        }
        /*
        第四步：收敛单子问题
        如果最终只剩一个子问题，且这个子问题和 rewrite 不一样，且模型也不主张拆分，那就统一用 rewrite 作为子问题。这样可以保证子问题和改写问题的一致性。
         */
        if (subQuestions.size() == 1 && !StrUtil.equals(subQuestions.get(0), rewrite) && !shouldSplit) {
            // 如果最终只剩一个子问题且模型并不主张拆分，则统一收敛为 rewrite。
            subQuestions = List.of(rewrite);
        }
        /*
        第五步：裁剪子问题数量
        最后，如果子问题数量超过了配置的上限（通常是 3-5 个），就截断到上限。这是为了控制后续检索的成本。
         */
        if (subQuestions.size() > properties.getMaxSubQuestions()) {
            // 子问题数量做上限裁剪，避免后续检索成本暴涨。
            subQuestions = subQuestions.subList(0, properties.getMaxSubQuestions());
        }
        return new RagRewriteResult(rewrite, subQuestions);
    }





    /*parse：解析模型输出
    解析模型原始输出。
 * 期望模型输出 JSON，包括 rewrite、should_split、sub_questions 等字段。
 * @param raw 模型原始输出
 * @return 解析结果；若结构不合法则返回 null
 _________________________________________________
 模型返回的 JSON 格式示例：
 {
  "rewrite": "Spring Boot 的核心特性是什么？",
  "should_split": false,
  "sub_questions": ["Spring Boot 的核心特性是什么？"]
}
或者多问句的情况：
{
  "rewrite": "Spring Boot 和 Spring Cloud 的区别与联系",
  "should_split": true,
  "sub_questions": [
    "Spring Boot 是什么？",
    "Spring Cloud 是什么？",
    "Spring Boot 和 Spring Cloud 有什么区别？"
  ]
}
_____________________
解析逻辑：
1.提取 rewrite 字段 - 改写后的问题，必填
2.提取 should_split 字段 - 是否应该拆分，可选
3.提取 sub_questions 数组 - 子问题列表，可选
如果 rewrite 为空或解析失败，返回 null，上层会走规则兜底。

     */
    private ParsedRewritePayload parse(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(raw.trim());
            String rewrite = root.path("rewrite").asText("").trim();
            if (StrUtil.isBlank(rewrite)) {
                return null;
            }
            Boolean shouldSplit = root.has("should_split") && !root.get("should_split").isNull()
                ? root.path("should_split").asBoolean(false)
                : null;
            List<String> parsedSubQuestions = new ArrayList<>();
            JsonNode subQuestionNode = root.path("sub_questions");
            if (subQuestionNode.isArray()) {
                subQuestionNode.forEach(item -> {
                    String text = item.asText("").trim();
                    if (StrUtil.isNotBlank(text)) {
                        parsedSubQuestions.add(text);
                    }
                });
            }
            return new ParsedRewritePayload(rewrite, shouldSplit, parsedSubQuestions);
        }
        catch (Exception exception) {
            // 解析失败只记录日志，后续由上层 fallback 接管。
            log.warn("解析问题改写结果失败，raw={}", raw, exception);
            return null;
        }
    }




    /**
     * 基于规则拆分多问句。
     *
     * @param question 原始问题
     * @return 子问题列表
     * _________________________________________________
     * 这个方法用正则表达式 [?？；;\\n]+ 来拆分问题，分隔符包括：
     * ? 和 ？ - 中英文问号
     * ； 和 ; - 中英文分号
     * \n - 换行符
     * 拆分后会做三件事：
     * 1.trim 和过滤空白 - 去掉首尾空格，过滤掉空字符串
     * 2.限制数量 - 通过 limit(properties.getMaxSubQuestions()) 限制子问题数量
     * 3.去重 - 通过 LinkedHashSet 去重，保持顺序
     * 为什么要限制子问题数量？
     * 如果用户一次问了 10 个问题，系统就要检索 10 次，成本会很高。所以配置里有个 maxSubQuestions 参数（通常设置为 3-5），超过这个数量的子问题会被截断。
     */
    private List<String> ruleBasedSplit(String question) {//规则拆分：按标点和换行分割，去重，限制数量
        List<String> result = Arrays.stream(question.split("[?？；;\\n]+"))
            .map(String::trim)
            .filter(StrUtil::isNotBlank)
            .limit(properties.getMaxSubQuestions())
            .toList();
        if (result.isEmpty()) {
            // 即使规则拆分失败，也至少返回原问题本身。
            return List.of(question);
        }
        // 去重后返回，避免重复子问题浪费后续检索预算。
        return new ArrayList<>(new LinkedHashSet<>(result));
    }

    private record ParsedRewritePayload(String rewrite, Boolean shouldSplit, List<String> subQuestions) {
    }
}


