package org.javaup.ai.manage.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 配置属性
 * @author: 阿星不是程序员
 **/
/**
 * 文档管理模块配置。
 *
 * <p>这里集中定义文档上传、MinIO 存储、Kafka 主题和切块策略的默认参数，
 * 方便后续按环境调整，而不需要把这些值写死在业务代码里。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.manage")
public class DocumentManageProperties {

    /**
     * MinIO 配置。
     */
    private Minio minio = new Minio();

    /**
     * Kafka 配置。
     */
    private Kafka kafka = new Kafka();

    /**
     * 切块配置。
     */
    private Chunk chunk = new Chunk();

    /**
     * 结构解析配置。
     */
    private StructureParsing structureParsing = new StructureParsing();

    /**
     * PGVector 配置。
     */
    private PgVector pgVector = new PgVector();

    /**
     * Elasticsearch 关键词检索配置。
     */
    private Elasticsearch elasticsearch = new Elasticsearch();

    /**
     * Neo4j 结构图配置。
     */
    private Neo4j neo4j = new Neo4j();

    @Data
    public static class Minio {
        private String endpoint = "http://127.0.0.1:9000";
        private String accessKey = "minioadmin";
        private String secretKey = "minioadmin";
        private String bucketName = "super-agent-document";
        private String objectPrefix = "rag/document";
        private String parsedTextPrefix = "rag/parsed-text";
    }

    @Data
    public static class Kafka {
        private String parseTopic = "super-agent-document-parse-route";
        private String indexTopic = "super-agent-document-index-build";
        private String groupId = "super-agent-document-manage";
        private Boolean autoCreateTopics = Boolean.TRUE;
    }

    @Data
    public static class Chunk {
        private Integer recursiveMaxChars = 800;
        private Integer recursiveOverlapChars = 120;
        private Integer semanticMaxChars = 700;
        private Integer semanticMinChars = 240;
        private Double semanticSimilarityThreshold = 0.18D;
        private Boolean llmEnabled = Boolean.FALSE;
        private Integer llmMaxChars = 3500;
        private Boolean recommendLlmWhenLowQuality = Boolean.TRUE;
    }

    @Data
    public static class StructureParsing {

        /**
         * 是否开启“代码主导 + LLM 判歧”的结构解析增强。
         */
        private Boolean llmDisambiguationEnabled = Boolean.TRUE;

        /**
         * 单次最多送给 LLM 判歧的候选行数。
         */
        private Integer maxAmbiguousSignalsPerCall = 8;

        /**
         * 每个歧义行在提示词里附带的上下文行数。
         */
        private Integer contextWindowLines = 2;

        /**
         * 普通标题候选允许的最大字符数。
         */
        private Integer maxPlainHeadingChars = 32;

        /**
         * 进入 LLM 判歧的最低置信度阈值。
         */
        private Double ambiguityConfidenceFloor = 0.45D;

        /**
         * 进入 LLM 判歧的最高置信度阈值。
         */
        private Double ambiguityConfidenceCeil = 0.80D;
    }

    @Data
    public static class PgVector {

        /**
         * 是否启用 PGVector 写入。
         */
        private Boolean enabled = Boolean.TRUE;

        /**
         * PostgreSQL 主机地址。
         */
        private String host = "127.0.0.1";

        /**
         * PostgreSQL 端口。
         */
        private Integer port = 5432;

        /**
         * PostgreSQL 数据库名。
         */
        private String database = "super_agent_pgvector";

        /**
         * PostgreSQL schema。
         */
        private String schema = "public";

        /**
         * PostgreSQL 用户名。
         */
        private String username = "postgres";

        /**
         * PostgreSQL 密码。
         */
        private String password = "postgres";

        /**
         * 连接池名称。
         */
        private String poolName = "super-agent-manage-pgvector-hikari";

        /**
         * 连接池最大连接数。
         */
        private Integer maximumPoolSize = 5;

        /**
         * 连接池最小空闲连接数。
         */
        private Integer minimumIdle = 1;
    }

    @Data
    public static class Elasticsearch {

        /**
         * 是否启用 Elasticsearch 关键词检索。
         */
        private Boolean enabled = Boolean.TRUE;

        /**
         * Elasticsearch 连接地址列表。
         */
        private List<String> uris = new java.util.ArrayList<>(List.of("http://127.0.0.1:9200"));

        /**
         * Elasticsearch 用户名。
         */
        private String username = "elastic";

        /**
         * Elasticsearch 密码。
         */
        private String password = "elastic";

        /**
         * 关键词索引名称。
         */
        private String indexName = "super_agent_document_keyword";

        /**
         * 建索引时使用的 analyzer。
         */
        private String analyzer = "ik_max_word";

        /**
         * 搜索时使用的 analyzer。
         */
        private String searchAnalyzer = "ik_smart";

        /**
         * 导航层结构节点索引名称。
         */
        private String navigationIndexName = "super_agent_document_navigation";

        /**
         * 知识路由元数据索引名称。
         */
        private String routeIndexName = "super_agent_knowledge_route";

        /**
         * 连接超时时间，毫秒。
         */
        private Integer connectTimeoutMillis = 3000;

        /**
         * 读超时时间，毫秒。
         */
        private Integer socketTimeoutMillis = 5000;
    }

    @Data
    public static class Neo4j {

        /**
         * 是否启用 Neo4j 结构图。
         */
        private Boolean enabled = Boolean.FALSE;

        /**
         * Bolt 地址。
         */
        private String uri = "bolt://127.0.0.1:7687";

        /**
         * 用户名。
         */
        private String username = "neo4j";

        /**
         * 密码。
         */
        private String password = "neo4j";

        /**
         * 数据库名称。
         */
        private String database = "neo4j";

        /**
         * 图查询超时时间，秒。
         */
        private Integer queryTimeoutSeconds = 5;
    }
}
