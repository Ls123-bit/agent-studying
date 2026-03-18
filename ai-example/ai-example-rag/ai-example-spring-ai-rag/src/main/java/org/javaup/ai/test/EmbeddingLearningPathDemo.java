package org.javaup.ai.test;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.Comparator;
import java.util.List;

/**
 * 演示如何使用 Embedding 给“学习诉求”和“课程路线”做语义匹配。
 */
public class EmbeddingLearningPathDemo {

    public static void main(String[] args) {
        String apiKey = "请替换成你的硅基流动的API Key";
        String apiUrl = "https://api.siliconflow.cn/v1/chat/completions";
        String modelName = "Qwen/Qwen3-Embedding-8B";

        EmbeddingModel embeddingModel = createEmbeddingModel(apiKey, apiUrl, modelName);

        List<LearningPath> learningPaths = List.of(
            new LearningPath(
                "P001",
                "Java 高并发与分布式实战",
                "已经掌握 Spring Boot，希望补齐缓存、消息队列、限流熔断和分布式事务的同学",
                "Spring Boot、Redis、Kafka、分布式锁、幂等设计、秒杀系统",
                "能独立设计高并发下单链路，并定位常见性能瓶颈"
            ),
            new LearningPath(
                "P002",
                "MySQL 与 Redis 性能优化路线",
                "经常写 SQL，但对索引设计、慢查询治理和缓存一致性还不够熟的同学",
                "索引优化、执行计划、锁机制、缓存击穿、缓存雪崩、热点 Key",
                "能系统分析数据库慢查询问题，并设计稳定的缓存策略"
            ),
            new LearningPath(
                "P003",
                "Spring AI 与 RAG 项目实战",
                "想用 Java 做 AI 应用，希望掌握文档切块、向量化、检索增强和答案生成流程的同学",
                "Spring AI、Embedding、向量数据库、Chunk、Prompt、RAG、知识库问答",
                "能搭建一个完整的智能问答系统，并理解从文本到召回的核心链路"
            ),
            new LearningPath(
                "P004",
                "前端工程化与交互设计",
                "需要独立完成管理后台或业务门户，希望系统掌握组件化和工程化实践的同学",
                "Vue、React、TypeScript、状态管理、构建优化、组件设计",
                "能设计清晰可维护的前端工程结构，并完成复杂页面开发"
            ),
            new LearningPath(
                "P005",
                "云原生部署与 DevOps 路线",
                "负责服务上线和日常运维，希望掌握容器化、CI/CD 和服务可观测性的同学",
                "Docker、Kubernetes、GitHub Actions、灰度发布、监控告警、日志采集",
                "能把 Java 服务稳定部署到容器平台，并建立基础运维体系"
            ),
            new LearningPath(
                "P006",
                "数据分析与可视化入门",
                "想做报表分析、经营复盘和指标看板，希望提升数据建模与图表表达能力的同学",
                "数据清洗、指标体系、BI 报表、可视化图表、业务分析、经营复盘",
                "能围绕业务问题搭建基础分析模型，并输出清晰的数据结论"
            )
        );

        String learnerNeed = """
            我已经会一点 Spring Boot，最近想做一个能读取文档、切分文本块、
            计算相似度并回答问题的 Java AI 项目，最好还能顺手学会向量检索和 RAG 的完整链路。
            """;

        runSemanticMatch(embeddingModel, learningPaths, learnerNeed, 3, modelName);
    }

    private static void runSemanticMatch(EmbeddingModel embeddingModel,
                                         List<LearningPath> learningPaths,
                                         String learnerNeed,
                                         int topK,
                                         String modelName) {
        List<String> pathPortraits = learningPaths.stream()
            .map(EmbeddingLearningPathDemo::buildPortrait)
            .toList();

        System.out.println("=== Embedding 学习路线匹配 Demo ===");
        System.out.println("Embedding 模型: " + modelName);
        System.out.println("候选路线数量: " + learningPaths.size());
        System.out.println();
        System.out.println("学习诉求:");
        System.out.println(learnerNeed);

        List<float[]> pathVectors = embeddingModel.embed(pathPortraits);
        float[] needVector = embeddingModel.embed(learnerNeed);

        System.out.println("向量生成完成，向量维度: " + pathVectors.get(0).length);
        System.out.println();
        System.out.println("--- Top 匹配结果 ---");

        List<MatchResult> results = learningPaths.stream()
            .map(path -> {
                int index = learningPaths.indexOf(path);
                double similarity = cosineSimilarity(needVector, pathVectors.get(index));
                return new MatchResult(path, similarity);
            })
            .sorted(Comparator.comparingDouble(MatchResult::similarity).reversed())
            .toList();

        results.stream()
            .limit(topK)
            .forEachOrdered(result -> printResult(result, buildPortrait(result.learningPath())));
    }

    private static EmbeddingModel createEmbeddingModel(String apiKey, String apiUrl, String modelName) {
        DashScopeApi dashScopeApi = DashScopeApi.builder()
            .baseUrl(apiUrl)
            .apiKey(apiKey)
            .build();

        DashScopeEmbeddingOptions options = DashScopeEmbeddingOptions.builder()
            .model(modelName)
            .build();

        return new DashScopeEmbeddingModel(dashScopeApi, MetadataMode.NONE, options);
    }

    private static String buildPortrait(LearningPath learningPath) {
        return """
            路线编号：%s
            路线名称：%s
            适合人群：%s
            核心关键词：%s
            学完之后：%s
            """.formatted(
            learningPath.code(),
            learningPath.title(),
            learningPath.audience(),
            learningPath.keywords(),
            learningPath.outcome()
        );
    }

    private static void printResult(MatchResult result, String portrait) {
        LearningPath path = result.learningPath();
        System.out.printf("[%s] %s -> 相似度 %.4f%n", path.code(), path.title(), result.similarity());
        System.out.println("画像内容:");
        System.out.println(portrait);
        System.out.println();
    }

    private static double cosineSimilarity(float[] left, float[] right) {
        if (left.length != right.length) {
            throw new IllegalArgumentException("向量维度不一致，无法计算相似度");
        }

        double dotProduct = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;

        for (int i = 0; i < left.length; i++) {
            dotProduct += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }

        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private record LearningPath(String code, String title, String audience, String keywords, String outcome) {
    }

    private record MatchResult(LearningPath learningPath, double similarity) {
    }

}
