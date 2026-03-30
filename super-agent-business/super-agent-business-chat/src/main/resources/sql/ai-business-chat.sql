CREATE TABLE IF NOT EXISTS super_agent_chat_session (
    id BIGINT NOT NULL COMMENT '主键id',
    conversation_id VARCHAR(64) NOT NULL COMMENT '会话ID',
    session_status TINYINT(1) NOT NULL DEFAULT '1' COMMENT '1:空闲 2:进行中',
    create_time DATETIME DEFAULT NULL COMMENT '创建时间',
    edit_time DATETIME DEFAULT NULL COMMENT '编辑时间',
    status TINYINT(1) DEFAULT '1' COMMENT '1:正常 0:删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_super_agent_chat_session_conversation_id (conversation_id),
    KEY idx_super_agent_chat_session_status (session_status, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='业务对话会话表';

CREATE TABLE IF NOT EXISTS super_agent_chat_turn (
    id BIGINT NOT NULL COMMENT '主键id',
    conversation_id VARCHAR(64) NOT NULL COMMENT '所属会话ID',
    question TEXT NOT NULL COMMENT '用户问题',
    answer LONGTEXT NOT NULL COMMENT '助手最终回答',
    thinking_steps JSON NOT NULL COMMENT '思考过程/工具提示列表',
    reference_list JSON NOT NULL COMMENT '引用来源列表',
    recommendation_list JSON NOT NULL COMMENT '推荐追问列表',
    used_tool_list JSON NOT NULL COMMENT '本轮使用的工具列表',
    turn_status TINYINT(1) NOT NULL DEFAULT '1' COMMENT '1:进行中 2:已完成 3:失败 4:已停止',
    error_message TEXT DEFAULT NULL COMMENT '失败或停止原因',
    first_response_time_ms BIGINT DEFAULT NULL COMMENT '首包耗时，毫秒',
    total_response_time_ms BIGINT DEFAULT NULL COMMENT '总耗时，毫秒',
    create_time DATETIME DEFAULT NULL COMMENT '创建时间',
    edit_time DATETIME DEFAULT NULL COMMENT '编辑时间',
    status TINYINT(1) DEFAULT '1' COMMENT '1:正常 0:删除',
    PRIMARY KEY (id),
    KEY idx_super_agent_chat_turn_conversation_id (conversation_id),
    KEY idx_super_agent_chat_turn_status (turn_status, status)
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
    KEY idx_graph_checkpoint_thread_saved (thread_id, saved_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Spring AI Alibaba Graph checkpoint 表';
