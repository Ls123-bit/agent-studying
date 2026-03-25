CREATE TABLE IF NOT EXISTS chat_session (
    conversation_id VARCHAR(64) NOT NULL COMMENT '会话ID',
    running TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否存在正在运行的轮次',
    created_at DATETIME(3) NOT NULL COMMENT '会话创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '会话更新时间',
    PRIMARY KEY (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='业务对话会话表';

CREATE TABLE IF NOT EXISTS chat_turn (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '轮次ID',
    conversation_id VARCHAR(64) NOT NULL COMMENT '所属会话ID',
    question TEXT NOT NULL COMMENT '用户问题',
    answer LONGTEXT NOT NULL COMMENT '助手最终回答',
    thinking_steps JSON NOT NULL COMMENT '思考过程/工具提示列表',
    `references` JSON NOT NULL COMMENT '引用来源列表',
    recommendations JSON NOT NULL COMMENT '推荐追问列表',
    used_tools JSON NOT NULL COMMENT '本轮使用的工具列表',
    status VARCHAR(32) NOT NULL COMMENT '轮次状态',
    error_message TEXT NULL COMMENT '失败或停止原因',
    first_response_time_ms BIGINT NULL COMMENT '首包耗时，毫秒',
    total_response_time_ms BIGINT NULL COMMENT '总耗时，毫秒',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_chat_turn_conversation_created (conversation_id, created_at),
    CONSTRAINT fk_chat_turn_session
        FOREIGN KEY (conversation_id)
        REFERENCES chat_session (conversation_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='业务对话轮次表';

CREATE TABLE IF NOT EXISTS GRAPH_THREAD (
    thread_id VARCHAR(36) NOT NULL COMMENT 'Graph 内部线程主键',
    thread_name VARCHAR(255) NOT NULL COMMENT '业务线程名，通常就是 conversationId',
    is_released BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已经被释放',
    PRIMARY KEY (thread_id),
    UNIQUE KEY IDX_GRAPH_THREAD_NAME_RELEASED (thread_name, is_released)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Spring AI Alibaba Graph 线程表';

CREATE TABLE IF NOT EXISTS GRAPH_CHECKPOINT (
    checkpoint_id VARCHAR(36) NOT NULL COMMENT 'Checkpoint ID',
    thread_id VARCHAR(36) NOT NULL COMMENT '关联线程ID',
    node_id VARCHAR(255) NULL COMMENT '当前节点ID',
    next_node_id VARCHAR(255) NULL COMMENT '下一个节点ID',
    state_data JSON NOT NULL COMMENT '序列化后的 Agent 状态',
    saved_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '保存时间',
    PRIMARY KEY (checkpoint_id),
    KEY idx_graph_checkpoint_thread_saved (thread_id, saved_at),
    CONSTRAINT GRAPH_FK_THREAD
        FOREIGN KEY (thread_id)
        REFERENCES GRAPH_THREAD (thread_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Spring AI Alibaba Graph checkpoint 表';
