package org.javaup.ai.prompt;



/*
常量类
所有模板名称的常量定义，方便代码中引用：
模板文件位置：
所有 .st 模板文件在 super-agent-business-chat 模块的
src/main/resources/prompt/ 目录下。想看具体的提示词内容直接去那里找，改完不用重新编译，重启即可生效（因为有缓存，生产环境需要重启）。
 */
public final class PromptTemplateNames {

    public static final String AGENT_QUESTION = "agent-question";//常量 AGENT_QUESTION 模板文件：agent-question.st 用途：最终发给Agent的问题
    public static final String CHAT_QUERY_REWRITE = "chat-query-rewrite";//常量 CHAT_QUERY_REWRITE 模板文件：chat-query-rewrite.st 用途：
    public static final String CONVERSATION_SUMMARY_MERGE = "conversation-summary-merge";//常量 CONVERSATION_SUMMARY_MERGE 模板文件：conversation-summary-merge.st 用途：摘要合并
    public static final String CONVERSATION_SUMMARY_SYSTEM = "conversation-summary-system";//常量 CONVERSATION_SUMMARY_SYSTEM 模板文件：conversation-summary-system.st 用途：摘要系统提示词
    public static final String DOCUMENT_GRAPH_ONLY_INTENT = "document-graph-only-intent";//常量 DOCUMENT_GRAPH_ONLY_INTENT 模板文件：document-graph-only-intent.st 用途：图结构意图分类
    public static final String DOCUMENT_LLM_SPLIT = "document-llm-split";//常量 DOCUMENT_LLM_SPLIT 模板文件：document-llm-split.st 用途：LLM 分块
    public static final String DOCUMENT_STRUCTURE_AMBIGUITY = "document-structure-ambiguity";//常量 DOCUMENT_STRUCTURE_AMBIGUITY 模板文件：document-structure-ambiguity.st 用途：结构歧义处理
    public static final String DOCUMENT_STRUCTURE_AMBIGUITY_CANDIDATE = "document-structure-ambiguity-candidate";//常量 DOCUMENT_STRUCTURE_AMBIGUITY_CANDIDATE 模板文件：document-structure-ambiguity-candidate.st 用途：歧义候选
    public static final String RAG_ANSWER_DOCUMENT_REFERENCE = "rag-answer-document-reference";//常量 RAG_ANSWER_DOCUMENT_REFERENCE 模板文件：rag-answer-document-reference.st 用途：文档引用块
    public static final String RAG_ANSWER_NO_EVIDENCE = "rag-answer-no-evidence";//常量 RAG_ANSWER_NO_EVIDENCE 模板文件：rag-answer-no-evidence.st 用途：无证据回复
    public static final String RAG_ANSWER_OMITTED_EVIDENCE = "rag-answer-omitted-evidence";//常量 RAG_ANSWER_OMITTED_EVIDENCE 模板文件：rag-answer-omitted-evidence.st 用途：省略证据提示
    public static final String RAG_ANSWER_REUSE_REFERENCE = "rag-answer-reuse-reference";//常量 RAG_ANSWER_REUSE_REFERENCE 模板文件：rag-answer-reuse-reference.st 用途：复用引用
    public static final String RAG_ANSWER_SUB_QUESTION_EVIDENCE = "rag-answer-sub-question-evidence"; //常量 RAG_ANSWER_SUB_QUESTION_EVIDENCE 模板文件：rag-answer-sub-question-evidence.st 用途：子问题证据块
    public static final String RAG_ANSWER_SYSTEM = "rag-answer-system";//常量 RAG_ANSWER_SYSTEM 模板文件：rag-answer-system.st 用途：RAG 回答系统提示词
    public static final String RAG_ANSWER_USER = "rag-answer-user";//常量 RAG_ANSWER_USER 模板文件：rag-answer-user.st 用途：RAG 回答用户提示词
    public static final String RAG_ANSWER_WEB_REFERENCE = "rag-answer-web-reference";//常量 RAG_ANSWER_WEB_REFERENCE 模板文件：rag-answer-web-reference.st 用途：网络引用块
    public static final String RECOMMENDATION_USER = "recommendation-user";//常量 RECOMMENDATION_USER 模板文件：recommendation-user.st 用途：推荐问题生成

    private PromptTemplateNames() {
    }
}
