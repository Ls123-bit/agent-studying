CREATE TABLE IF NOT EXISTS super_agent_chat_dialogue (
    id BIGINT NOT NULL COMMENT '主键id',
    dialogue_code VARCHAR(64) NOT NULL COMMENT '业务会话编号',
    dialogue_stage TINYINT(1) NOT NULL DEFAULT '1' COMMENT '1:空闲 2:进行中',
    create_time DATETIME DEFAULT NULL COMMENT '创建时间',
    edit_time DATETIME DEFAULT NULL COMMENT '编辑时间',
    status TINYINT(1) DEFAULT '1' COMMENT '1:正常 0:删除',
    PRIMARY KEY (id),
    KEY idx_super_agent_chat_dialogue_code_status (dialogue_code, status),
    KEY idx_super_agent_chat_dialogue_stage_status (dialogue_stage, status),
    KEY idx_super_agent_chat_dialogue_edit_time (edit_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='业务对话归档主表';

CREATE TABLE IF NOT EXISTS super_agent_chat_exchange (
    id BIGINT NOT NULL COMMENT '主键id',
    dialogue_code VARCHAR(64) NOT NULL COMMENT '所属业务会话编号',
    user_prompt TEXT NOT NULL COMMENT '用户提问',
    reply_content LONGTEXT NOT NULL COMMENT '助手回答内容',
    reasoning_note_list JSON NOT NULL COMMENT '过程提示与思考片段',
    source_snapshot_list JSON NOT NULL COMMENT '引用来源快照',
    followup_suggestion_list JSON NOT NULL COMMENT '推荐追问快照',
    tool_trace_list JSON NOT NULL COMMENT '工具使用轨迹快照',
    debug_trace_json JSON DEFAULT NULL COMMENT '调试轨迹快照',
    exchange_state TINYINT(1) NOT NULL DEFAULT '1' COMMENT '1:进行中 2:已完成 3:失败 4:已停止',
    finish_note TEXT DEFAULT NULL COMMENT '失败或终止说明',
    first_token_latency_ms BIGINT DEFAULT NULL COMMENT '首包耗时，毫秒',
    total_latency_ms BIGINT DEFAULT NULL COMMENT '总耗时，毫秒',
    create_time DATETIME DEFAULT NULL COMMENT '创建时间',
    edit_time DATETIME DEFAULT NULL COMMENT '编辑时间',
    status TINYINT(1) DEFAULT '1' COMMENT '1:正常 0:删除',
    PRIMARY KEY (id),
    KEY idx_super_agent_chat_exchange_dialogue_status (dialogue_code, status),
    KEY idx_super_agent_chat_exchange_state_status (exchange_state, status),
    KEY idx_super_agent_chat_exchange_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='业务对话轮次归档表';

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
