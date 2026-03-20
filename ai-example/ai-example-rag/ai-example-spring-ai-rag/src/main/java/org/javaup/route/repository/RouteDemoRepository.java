package org.javaup.route.repository;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 用内存数据模拟知识库和业务系统，方便示例直接跑起来。
 * 线上项目把这里替换成向量库、数据库或下游接口即可。
 */
@Component
public class RouteDemoRepository {

    private static final List<KnowledgeDoc> KNOWLEDGE_DOCS = List.of(
        new KnowledgeDoc(
            "kb-invoice",
            "训练营发票开具规则",
            """
                JavaUp 训练营支持开具电子普通发票。学员在支付成功后的 30 天内都可以申请。
                开票入口在“我的订单 -> 发票申请”，发票抬头支持个人和企业两种类型。
                企业发票需要补充税号和邮箱，系统会在 1 个工作日内发送 PDF 发票。
                """,
            List.of("发票", "电子发票", "开票", "税号", "订单")
        ),
        new KnowledgeDoc(
            "kb-replay",
            "直播课回放与补课说明",
            """
                JavaUp 训练营的直播课会在课程结束后 2 小时内生成回放。
                正常开营期间，学员可以无限次观看当期班级的回放视频。
                如果因为加班错过直播，也不需要单独补课，直接观看回放并完成当日作业即可。
                """,
            List.of("回放", "直播课", "补课", "加班", "视频")
        ),
        new KnowledgeDoc(
            "kb-certificate",
            "结课证书发放规则",
            """
                结课证书不是自动发放的，需要同时满足三个条件：
                第一，课程进度达到 85% 以上；
                第二，实战作业提交率不低于 80%；
                第三，结营测评达到合格线。
                满足条件后，系统会在结营后一周内发放电子证书。
                """,
            List.of("证书", "结课", "发放", "作业", "测评")
        ),
        new KnowledgeDoc(
            "kb-refund",
            "训练营退款规则",
            """
                训练营购买后 48 小时内，且未解锁超过 20% 的课程内容，可以申请无忧退款。
                如果已经进入正式实战辅导阶段，默认不再支持全额退款，但可以联系班主任确认是否满足特殊售后条件。
                退款规则属于平台通用政策，和某个具体订单的执行结果是两回事。
                """,
            List.of("退款", "售后", "订单", "无忧退款", "退费")
        )
    );

    private static final Map<String, OrderSnapshot> ORDER_STORE = Map.of(
        "ju-20260318-1001", new OrderSnapshot(
            "JU-20260318-1001",
            "AI 大模型 RAG 实战营",
            "已支付",
            "学习权限已开通",
            true,
            "如需售后，请在订单页发起申请"
        ),
        "ju-20260318-1002", new OrderSnapshot(
            "JU-20260318-1002",
            "Spring AI Agent 落地班",
            "待支付",
            "暂未开通学习权限",
            false,
            "请先完成支付，支付后会自动开通课程"
        )
    );

    private static final Map<String, LearningProgressSnapshot> LEARNING_STORE = Map.of(
        "demo-user", new LearningProgressSnapshot(
            "DEMO-USER",
            "AI 大模型 RAG 实战营",
            "CAMP-RAG-03",
            72,
            "第 08 课：检索路由策略",
            1
        ),
        "stu-1002", new LearningProgressSnapshot(
            "STU-1002",
            "Spring AI Agent 落地班",
            "CAMP-AGENT-01",
            41,
            "第 04 课：Tool Calling 设计",
            3
        )
    );

    private static final Map<String, LiveClassSchedule> SCHEDULE_STORE = Map.of(
        "camp-rag-03", new LiveClassSchedule(
            "CAMP-RAG-03",
            "2026-03-20 20:00",
            "多路由检索与答案兜底策略"
        ),
        "camp-agent-01", new LiveClassSchedule(
            "CAMP-AGENT-01",
            "2026-03-22 19:30",
            "Spring AI Alibaba 的 ReactAgent 实战"
        )
    );

    /**
     * 这是一个非常轻量的“伪检索”：
     * - 先看标签是否命中
     * - 再看整句和分词是否命中
     * 目标不是做高精度搜索，而是让路由示例不依赖向量库也能跑通。
     */
    public List<KnowledgeDoc> searchKnowledge(String query) {
        String normalized = normalize(query);
        return KNOWLEDGE_DOCS.stream()
            .map(doc -> Map.entry(doc, score(doc, normalized)))
            .filter(entry -> entry.getValue() > 0)
            .sorted(Map.Entry.<KnowledgeDoc, Integer>comparingByValue(Comparator.reverseOrder()))
            .limit(3)
            .map(Map.Entry::getKey)
            .toList();
    }

    public Optional<OrderSnapshot> findOrder(String orderId) {
        return Optional.ofNullable(ORDER_STORE.get(normalize(orderId)));
    }

    public Optional<LearningProgressSnapshot> findLearningProgress(String studentId) {
        return Optional.ofNullable(LEARNING_STORE.get(normalize(studentId)));
    }

    public Optional<LiveClassSchedule> findSchedule(String classId) {
        return Optional.ofNullable(SCHEDULE_STORE.get(normalize(classId)));
    }

    private int score(KnowledgeDoc doc, String normalizedQuery) {
        if (!StringUtils.hasText(normalizedQuery)) {
            return 0;
        }
        int score = 0;
        // 标签命中权重更高，因为更像“人工打过标”的关键词。
        for (String tag : doc.getTags()) {
            if (normalizedQuery.contains(normalize(tag))) {
                score += 3;
            }
        }
        if (normalizedQuery.length() >= 4 && doc.searchText().contains(normalizedQuery)) {
            score += 2;
        }
        for (String token : splitKeywords(normalizedQuery)) {
            if (doc.searchText().contains(token)) {
                score += 1;
            }
        }
        return score;
    }

    private List<String> splitKeywords(String text) {
        return Stream.of(text.split("[，,。；;？?、\\s]+"))
            .map(String::trim)
            .filter(token -> token.length() >= 2)
            .toList();
    }

    private String normalize(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.trim().toLowerCase(Locale.ROOT);
    }

    public static class KnowledgeDoc {

        private final String id;
        private final String title;
        private final String content;
        private final List<String> tags;

        public KnowledgeDoc(String id, String title, String content, List<String> tags) {
            this.id = id;
            this.title = title;
            this.content = content;
            this.tags = tags == null ? List.of() : List.copyOf(tags);
        }

        public String getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public String getContent() {
            return content;
        }

        public List<String> getTags() {
            return tags;
        }

        public String searchText() {
            return (title + " " + content + " " + String.join(" ", tags)).toLowerCase(Locale.ROOT);
        }
    }

    public static class OrderSnapshot {

        private final String orderId;
        private final String courseName;
        private final String payStatus;
        private final String deliveryStatus;
        private final boolean refundable;
        private final String suggestion;

        public OrderSnapshot(String orderId,
                             String courseName,
                             String payStatus,
                             String deliveryStatus,
                             boolean refundable,
                             String suggestion) {
            this.orderId = orderId;
            this.courseName = courseName;
            this.payStatus = payStatus;
            this.deliveryStatus = deliveryStatus;
            this.refundable = refundable;
            this.suggestion = suggestion;
        }

        public String getOrderId() {
            return orderId;
        }

        public String getCourseName() {
            return courseName;
        }

        public String getPayStatus() {
            return payStatus;
        }

        public String getDeliveryStatus() {
            return deliveryStatus;
        }

        public boolean isRefundable() {
            return refundable;
        }

        public String getSuggestion() {
            return suggestion;
        }
    }

    public static class LearningProgressSnapshot {

        private final String studentId;
        private final String courseName;
        private final String campId;
        private final int progressPercent;
        private final String latestLesson;
        private final int pendingHomeworkCount;

        public LearningProgressSnapshot(String studentId,
                                        String courseName,
                                        String campId,
                                        int progressPercent,
                                        String latestLesson,
                                        int pendingHomeworkCount) {
            this.studentId = studentId;
            this.courseName = courseName;
            this.campId = campId;
            this.progressPercent = progressPercent;
            this.latestLesson = latestLesson;
            this.pendingHomeworkCount = pendingHomeworkCount;
        }

        public String getStudentId() {
            return studentId;
        }

        public String getCourseName() {
            return courseName;
        }

        public String getCampId() {
            return campId;
        }

        public int getProgressPercent() {
            return progressPercent;
        }

        public String getLatestLesson() {
            return latestLesson;
        }

        public int getPendingHomeworkCount() {
            return pendingHomeworkCount;
        }
    }

    public static class LiveClassSchedule {

        private final String campId;
        private final String nextClassTime;
        private final String topic;

        public LiveClassSchedule(String campId, String nextClassTime, String topic) {
            this.campId = campId;
            this.nextClassTime = nextClassTime;
            this.topic = topic;
        }

        public String getCampId() {
            return campId;
        }

        public String getNextClassTime() {
            return nextClassTime;
        }

        public String getTopic() {
            return topic;
        }
    }
}
