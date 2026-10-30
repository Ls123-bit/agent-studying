package org.javaup.ai.office.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaup.ai.office.data.AttendanceMonthlySummary;
import org.javaup.ai.office.data.AttendanceRecord;
import org.javaup.ai.office.repository.AttendanceRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/*
办公场景工具（考勤）。

checkAttendance 读真实数据：nexus_agent_attendance_record（见 sql/nexus_agent_attendance_record.sql），
按"工号 + 月份"取明细后在内存里聚合，不再返回写死的数字。
数据库不可用时不抛异常，而是返回 success=false 的结构化 JSON——异常抛出去模型只会看到一句含糊的工具失败，
返回明确的 message 反而更容易被正确解释、也更好排查。
 */
@Service
public class AttendanceTools {

    private static final Logger log = LoggerFactory.getLogger(AttendanceTools.class);

    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private static final String SOURCE_TABLE = "nexus_agent_attendance_record";

    private final AttendanceRecordRepository attendanceRecordRepository;
    private final ObjectMapper objectMapper;

    public AttendanceTools(AttendanceRecordRepository attendanceRecordRepository, ObjectMapper objectMapper) {
        this.attendanceRecordRepository = attendanceRecordRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询员工考勤记录
     * 大模型会根据description判断何时使用此工具
     */
    @Tool(description = "查询员工的考勤记录，包括出勤天数、迟到次数、早退次数、请假天数、加班时长和每日明细。" +
                        "当用户询问考勤、打卡、出勤等相关问题时使用此工具。")
    public String checkAttendance(
            @ToolParam(description = "员工工号，如E10086") String employeeId,
            @ToolParam(description = "查询月份，格式YYYY-MM，如2026-10") String month) {

        // 工号统一大写：库里的工号是 E 开头大写，模型偶尔会传 e10086。
        String normalizedEmployeeId = employeeId == null ? "" : employeeId.trim().toUpperCase(Locale.ROOT);
        if (normalizedEmployeeId.isEmpty()) {
            return errorJson("", month, "employeeId 不能为空");
        }

        YearMonth targetMonth;
        try {
            targetMonth = YearMonth.parse(month == null ? "" : month.trim(), MONTH_FORMAT);
        } catch (DateTimeParseException exception) {
            return errorJson(normalizedEmployeeId, month, "month 格式应为 YYYY-MM，例如 2026-10");
        }

        try {
            List<AttendanceRecord> records =
                attendanceRecordRepository.findByEmployeeAndMonth(normalizedEmployeeId, targetMonth);
            return summaryJson(normalizedEmployeeId, targetMonth, records);
        } catch (DataAccessException exception) {
            log.warn("查询考勤明细失败：employeeId={}，month={}", normalizedEmployeeId, targetMonth, exception);
            return errorJson(normalizedEmployeeId, targetMonth.toString(), "考勤数据源暂不可用，请稍后重试");
        }
    }

    /**
     * 员工签到打卡
     * 仍为演示实现：只回显打卡结果，不写 nexus_agent_attendance_record。
     */
    @Tool(description = "员工进行签到打卡操作。当用户说要打卡、签到时使用此工具。")
    public String clockIn(
            @ToolParam(description = "员工工号") String employeeId,
            @ToolParam(description = "打卡类型：IN表示上班签到，OUT表示下班签退") String type) {

        String currentTime = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        String status = "IN".equalsIgnoreCase(type) ? "上班签到" : "下班签退";

        return String.format("""
            {
                "success": true,
                "employeeId": "%s",
                "clockType": "%s",
                "clockTime": "%s",
                "message": "%s成功"
            }
            """, employeeId, type, currentTime, status);
    }

    /*
    正常结果：汇总字段保持改造前的字段名（employeeId / month / workDays / actualDays / lateTimes /
    earlyLeaveTimes / leaveDays / overtimeHours），避免已依赖这些字段的提示词和前端跟着改；
    新增 employeeName / absentDays / dailyRecords / source 只是补充信息。
     */
    private String summaryJson(String employeeId, YearMonth month, List<AttendanceRecord> records) {
        AttendanceMonthlySummary summary = AttendanceMonthlySummary.of(records);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("employeeId", employeeId);
        payload.put("employeeName", records.isEmpty() ? null : records.get(0).employeeName());
        payload.put("month", month.toString());
        payload.put("workDays", summary.workDays());
        payload.put("actualDays", summary.actualDays());
        payload.put("lateTimes", summary.lateTimes());
        payload.put("earlyLeaveTimes", summary.earlyLeaveTimes());
        payload.put("leaveDays", summary.leaveDays());
        payload.put("absentDays", summary.absentDays());
        payload.put("overtimeHours", summary.overtimeHours());
        if (records.isEmpty()) {
            payload.put("message", "该员工在 " + month + " 没有考勤记录");
        }
        payload.put("dailyRecords", records.stream().map(this::dailyRecord).toList());
        payload.put("source", SOURCE_TABLE);

        return writeJson(payload);
    }

    private Map<String, Object> dailyRecord(AttendanceRecord record) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("date", record.attendanceDate().toString());
        item.put("status", record.statusName());
        item.put("clockIn", record.clockInTime() == null ? null : record.clockInTime().format(TIME_FORMAT));
        item.put("clockOut", record.clockOutTime() == null ? null : record.clockOutTime().format(TIME_FORMAT));
        item.put("workMinutes", record.workMinutes());
        item.put("overtimeMinutes", record.overtimeMinutes());
        item.put("leaveType", record.leaveType());
        item.put("remark", record.remark());
        return item;
    }

    private String errorJson(String employeeId, String month, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", false);
        payload.put("employeeId", employeeId);
        payload.put("month", month);
        payload.put("message", message);
        return writeJson(payload);
    }

    /*
    Jackson 序列化理论上不会失败（都是基本类型和字符串），
    仍然兜一层：工具方法返回 null 会让模型拿到空内容，不如退化成一条可读的错误文本。
     */
    private String writeJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            log.warn("考勤结果序列化失败", exception);
            return "{\"success\":false,\"message\":\"考勤结果序列化失败\"}";
        }
    }
}
