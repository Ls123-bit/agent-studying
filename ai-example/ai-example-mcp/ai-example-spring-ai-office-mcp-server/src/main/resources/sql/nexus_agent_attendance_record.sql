-- =====================================================================
-- 员工考勤明细表（office-tools MCP server 的真实数据来源）
-- 作用：AttendanceTools.checkAttendance 从这里按月聚合出勤/迟到/早退/请假/加班。
-- 生效方式：MCP server 启动时由 AttendanceRecordRepository 自动执行本脚本（幂等）。
--           CREATE TABLE IF NOT EXISTS + INSERT IGNORE，重复启动不会报错、也不会覆盖已改动的数据；
--           演示数据只在对应行不存在时补齐，删掉某行后重启即可恢复。
-- 表位置：与主应用共用 nexus_agent 库，表名前缀保持 nexus_agent_ 风格。
-- =====================================================================
CREATE TABLE IF NOT EXISTS `nexus_agent_attendance_record`
(
    `id`               bigint  NOT NULL AUTO_INCREMENT COMMENT '主键id',
    `employee_id`      varchar(32)  NOT NULL COMMENT '员工工号，例如 E10086',
    `employee_name`    varchar(64)  DEFAULT NULL COMMENT '员工姓名快照',
    `attendance_date`  date         NOT NULL COMMENT '考勤日期',
    `attendance_status` tinyint     NOT NULL DEFAULT '1' COMMENT '考勤状态 1:正常 2:迟到 3:早退 4:请假 5:缺勤',
    `clock_in_time`    datetime     DEFAULT NULL COMMENT '上班签到时间，请假/缺勤为空',
    `clock_out_time`   datetime     DEFAULT NULL COMMENT '下班签退时间，未签退为空',
    `work_minutes`     int          DEFAULT NULL COMMENT '当日有效工时（分钟）',
    `overtime_minutes` int          DEFAULT '0' COMMENT '当日加班时长（分钟）',
    `leave_type`       varchar(32)  DEFAULT NULL COMMENT '请假类型 ANNUAL:年假 SICK:病假 PERSONAL:事假，非请假为空',
    `remark`           varchar(255) DEFAULT NULL COMMENT '备注，例如迟到原因',
    `create_time`      datetime     DEFAULT NULL COMMENT '创建时间',
    `edit_time`        datetime     DEFAULT NULL COMMENT '编辑时间',
    `status`           tinyint(1)   DEFAULT '1' COMMENT '1:正常 0:删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_attendance_employee_date` (`employee_id`, `attendance_date`),
    KEY `idx_attendance_date` (`attendance_date`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='员工考勤明细表';

-- 演示数据：E10086 王小明在 2026-10 的 22 个工作日
-- （10-12、10-26 迟到；10-15 年假；加班合计 510 分钟 = 8.5 小时，与改造前 mock 数字一致，便于对照验证）
INSERT IGNORE INTO `nexus_agent_attendance_record`
(`employee_id`, `employee_name`, `attendance_date`, `attendance_status`, `clock_in_time`, `clock_out_time`,
 `work_minutes`, `overtime_minutes`, `leave_type`, `remark`, `create_time`, `edit_time`, `status`)
VALUES ('E10086', '王小明', '2026-10-01', 1, '2026-10-01 08:52:00', '2026-10-01 18:05:00', 480, 0, NULL, NULL, NOW(), NOW(), 1),
       ('E10086', '王小明', '2026-10-02', 1, '2026-10-02 08:58:00', '2026-10-02 18:02:00', 480, 0, NULL, NULL, NOW(), NOW(), 1),
       ('E10086', '王小明', '2026-10-05', 1, '2026-10-05 08:47:00', '2026-10-05 18:12:00', 480, 0, NULL, NULL, NOW(), NOW(), 1),
       ('E10086', '王小明', '2026-10-06', 1, '2026-10-06 08:55:00', '2026-10-06 18:08:00', 480, 0, NULL, NULL, NOW(), NOW(), 1),
       ('E10086', '王小明', '2026-10-07', 1, '2026-10-07 08:50:00', '2026-10-07 18:03:00', 480, 0, NULL, NULL, NOW(), NOW(), 1),
       ('E10086', '王小明', '2026-10-08', 1, '2026-10-08 08:56:00', '2026-10-08 18:20:00', 480, 0, NULL, NULL, NOW(), NOW(), 1),
       ('E10086', '王小明', '2026-10-09', 1, '2026-10-09 08:53:00', '2026-10-09 18:06:00', 480, 0, NULL, NULL, NOW(), NOW(), 1),
       ('E10086', '王小明', '2026-10-12', 2, '2026-10-12 09:23:00', '2026-10-12 18:05:00', 480, 0, NULL, '地铁故障', NOW(), NOW(), 1),
       ('E10086', '王小明', '2026-10-13', 1, '2026-10-13 08:49:00', '2026-10-13 18:04:00', 480, 0, NULL, NULL, NOW(), NOW(), 1),
       ('E10086', '王小明', '2026-10-14', 1, '2026-10-14 08:54:00', '2026-10-14 18:11:00', 480, 0, NULL, NULL, NOW(), NOW(), 1),
       ('E10086', '王小明', '2026-10-15', 4, NULL, NULL, NULL, 0, 'ANNUAL', '年假一天', NOW(), NOW(), 1),
       ('E10086', '王小明', '2026-10-16', 1, '2026-10-16 08:51:00', '2026-10-16 18:07:00', 480, 0, NULL, NULL, NOW(), NOW(), 1),
       ('E10086', '王小明', '2026-10-19', 1, '2026-10-19 08:57:00', '2026-10-19 18:09:00', 480, 0, NULL, NULL, NOW(), NOW(), 1),
       ('E10086', '王小明', '2026-10-20', 1, '2026-10-20 08:48:00', '2026-10-20 18:15:00', 480, 0, NULL, NULL, NOW(), NOW(), 1),
       ('E10086', '王小明', '2026-10-21', 1, '2026-10-21 08:52:00', '2026-10-21 18:06:00', 480, 0, NULL, NULL, NOW(), NOW(), 1),
       ('E10086', '王小明', '2026-10-22', 1, '2026-10-22 08:55:00', '2026-10-22 20:00:00', 480, 120, NULL, '版本发布值守', NOW(), NOW(), 1),
       ('E10086', '王小明', '2026-10-23', 1, '2026-10-23 08:50:00', '2026-10-23 18:04:00', 480, 0, NULL, NULL, NOW(), NOW(), 1),
       ('E10086', '王小明', '2026-10-26', 2, '2026-10-26 09:15:00', '2026-10-26 18:30:00', 480, 0, NULL, '早高峰拥堵', NOW(), NOW(), 1),
       ('E10086', '王小明', '2026-10-27', 1, '2026-10-27 08:56:00', '2026-10-27 18:08:00', 480, 0, NULL, NULL, NOW(), NOW(), 1),
       ('E10086', '王小明', '2026-10-28', 1, '2026-10-28 08:53:00', '2026-10-28 21:00:00', 480, 180, NULL, '故障复盘会', NOW(), NOW(), 1),
       ('E10086', '王小明', '2026-10-29', 1, '2026-10-29 08:49:00', '2026-10-29 18:05:00', 480, 0, NULL, NULL, NOW(), NOW(), 1),
       ('E10086', '王小明', '2026-10-30', 1, '2026-10-30 08:54:00', '2026-10-30 21:30:00', 480, 210, NULL, '月末结算', NOW(), NOW(), 1);

-- 第二个员工：用于验证按工号隔离（E10086 的统计里不会出现这些行）
INSERT IGNORE INTO `nexus_agent_attendance_record`
(`employee_id`, `employee_name`, `attendance_date`, `attendance_status`, `clock_in_time`, `clock_out_time`,
 `work_minutes`, `overtime_minutes`, `leave_type`, `remark`, `create_time`, `edit_time`, `status`)
VALUES ('E10087', '李静', '2026-10-08', 1, '2026-10-08 08:58:00', '2026-10-08 18:02:00', 480, 0, NULL, NULL, NOW(), NOW(), 1),
       ('E10087', '李静', '2026-10-09', 5, NULL, NULL, NULL, 0, NULL, '未打卡且未请假', NOW(), NOW(), 1),
       ('E10087', '李静', '2026-10-12', 3, '2026-10-12 08:50:00', '2026-10-12 16:10:00', 440, 0, NULL, '身体不适提前离岗', NOW(), NOW(), 1);
