package org.javaup.route.service;

import lombok.extern.slf4j.Slf4j;
import org.javaup.route.model.RouteHandledResult;
import org.javaup.route.model.RouteMessage;
import org.javaup.route.repository.RouteDemoRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具调用通道。
 * 为了让示例开箱即跑，这里用内存数据模拟工具返回，再用大模型组织成自然语言。
 */
@Slf4j
@Service
public class ToolRouteService {

    private static final Pattern ORDER_ID_PATTERN = Pattern.compile("(?i)JU[-_]?\\d{8}[-_]?\\d{4}");
    private static final Pattern STUDENT_ID_PATTERN = Pattern.compile("(?i)(DEMO-USER|STU-\\d{4})");
    private static final Pattern CAMP_ID_PATTERN = Pattern.compile("(?i)CAMP-[A-Z]+-\\d{2}");

    private static final String TOOL_PROMPT = """
        你是 JavaUp 学习平台的业务助手，请根据工具返回的事实回答用户。

        回答要求：
        - 只基于工具结果回答，不要编造不存在的订单或进度。
        - 如果工具结果提示缺少编号，就明确告诉用户需要补什么。
        - 如果查到了结果，用 2 到 4 句话说清楚结论和下一步建议。
        - 输出自然中文，不要返回 JSON。
        """;

    private final ChatClient chatClient;
    private final RouteDemoRepository repository;

    public ToolRouteService(ChatClient.Builder builder, RouteDemoRepository repository) {
        this.chatClient = builder.defaultSystem(TOOL_PROMPT).build();
        this.repository = repository;
    }

    /**
     * 工具通道主流程：
     * 1. 先决定该查哪类业务数据
     * 2. 拿到“工具结果”
     * 3. 再把结果整理成自然语言回复
     */
    public RouteHandledResult answer(String question, List<RouteMessage> history) {
        String toolResult = resolveToolResult(question);
        String answer = renderAnswer(question, history, toolResult);
        return new RouteHandledResult("tool", answer, question, List.of(toolResult));
    }

    /**
     * 示例里模拟了三类工具：
     * - 订单查询
     * - 学习进度查询
     * - 直播排期查询
     */
    private String resolveToolResult(String question) {
        String normalized = normalize(question);

        // 订单问题优先尝试抽订单号。
        if (normalized.contains("订单") || normalized.contains("支付")) {
            Optional<String> orderId = extract(ORDER_ID_PATTERN, question);
            if (orderId.isEmpty()) {
                return "缺少订单号。请补充类似 JU-20260318-1001 这样的订单编号，我才能继续查询。";
            }
            return repository.findOrder(orderId.get())
                .map(order -> """
                    工具=queryOrder
                    订单号=%s
                    课程=%s
                    支付状态=%s
                    权限状态=%s
                    是否支持退款=%s
                    建议=%s
                    """.formatted(
                    order.getOrderId(),
                    order.getCourseName(),
                    order.getPayStatus(),
                    order.getDeliveryStatus(),
                    order.isRefundable() ? "支持" : "暂不支持",
                    order.getSuggestion()
                ))
                .orElse("未查到这个订单号，请先确认是否输入了正确的订单编号。");
        }

        // 学习进度类问题默认兜底到演示学员，保证示例直接可跑。
        if (normalized.contains("进度") || normalized.contains("学到哪") || normalized.contains("作业")) {
            String studentId = extract(STUDENT_ID_PATTERN, question).orElse("DEMO-USER");
            return repository.findLearningProgress(studentId)
                .map(progress -> """
                    工具=queryLearningProgress
                    学员ID=%s
                    课程=%s
                    班级=%s
                    当前进度=%d%%
                    最近学习章节=%s
                    待完成作业数=%d
                    """.formatted(
                    progress.getStudentId(),
                    progress.getCourseName(),
                    progress.getCampId(),
                    progress.getProgressPercent(),
                    progress.getLatestLesson(),
                    progress.getPendingHomeworkCount()
                ))
                .orElse("没有查到这个学员 ID 对应的学习进度，请检查编号后再试。");
        }

        // 班级排期类问题同理，没给班级编号就走演示班级。
        if (normalized.contains("直播") || normalized.contains("排期") || normalized.contains("上课")) {
            String campId = extract(CAMP_ID_PATTERN, question).orElse("CAMP-RAG-03");
            return repository.findSchedule(campId)
                .map(schedule -> """
                    工具=queryLiveClassSchedule
                    班级=%s
                    下次直播时间=%s
                    主题=%s
                    """.formatted(
                    schedule.getCampId(),
                    schedule.getNextClassTime(),
                    schedule.getTopic()
                ))
                .orElse("没有查到这个班级编号的直播排期，请先确认班级编号是否正确。");
        }

        return "当前问题看起来像工具查询，但还缺少清晰的对象。你可以补充订单号、学员 ID 或班级编号，我再继续查。";
    }

    /**
     * 工具返回的是结构化事实，这里再交给模型润色成人能直接看的回复。
     */
    private String renderAnswer(String question, List<RouteMessage> history, String toolResult) {
        try {
            return chatClient.prompt()
                .user(user -> user.text("""
                    用户问题：{question}

                    历史对话：
                    {history}

                    工具结果：
                    {toolResult}
                    """)
                    .param("question", question)
                    .param("history", formatHistory(history))
                    .param("toolResult", toolResult))
                .call()
                .content();
        }
        catch (Exception exception) {
            log.warn("工具结果润色失败，直接返回原始结果: {}", exception.getMessage());
            return toolResult;
        }
    }

    /**
     * 从自然语言里抽取业务编号。
     */
    private Optional<String> extract(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(matcher.group().toUpperCase(Locale.ROOT).replace('_', '-'));
    }

    private String formatHistory(List<RouteMessage> history) {
        if (history == null || history.isEmpty()) {
            return "无历史对话";
        }
        StringBuilder builder = new StringBuilder();
        for (RouteMessage message : history) {
            String role = "assistant".equalsIgnoreCase(message.getRole()) ? "助手" : "用户";
            builder.append(role).append("：").append(message.getContent()).append("\n");
        }
        return builder.toString().strip();
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
    }
}
