package org.javaup.ai.manage.support;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 常量类
 * @author: 阿星不是程序员
 **/
/**
 * PGVector 相关常量。
 *
 * <p>当前第一期统一使用单表保存所有文档 chunk 的向量，
 * 写入与检索都围绕这张表展开，避免表名在多处硬编码后不一致。</p>
 */
public final class DocumentPgVectorConstants {

    /**
     * PGVector 文档向量表名。
     */
    public static final String EMBEDDING_TABLE_NAME = "public.super_agent_document_embedding";

    private DocumentPgVectorConstants() {
    }
}
