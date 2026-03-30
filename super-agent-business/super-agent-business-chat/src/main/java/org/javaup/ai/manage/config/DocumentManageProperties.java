package org.javaup.ai.manage.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
     * PGVector 配置。
     */
    private PgVector pgVector = new PgVector();

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
        private Integer semanticMaxChars = 700;
        private Integer semanticMinChars = 240;
        private Double semanticSimilarityThreshold = 0.18D;
        private Boolean llmEnabled = Boolean.FALSE;
        private Integer llmMaxChars = 3500;
        private Boolean recommendLlmWhenLowQuality = Boolean.TRUE;
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
}
