package org.javaup.ai.office.data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/*
考勤明细行（nexus_agent_attendance_record 的一条记录）。

attendance_status 沿用项目里 tinyint 编码的习惯（和 parse_status / task_status 一致），
语义集中在 statusName() 里，避免编码含义散落到多个类。
 */
public record AttendanceRecord(String employeeId,
                               String employeeName,
                               LocalDate attendanceDate,
                               int attendanceStatus,
                               LocalDateTime clockInTime,
                               LocalDateTime clockOutTime,
                               Integer workMinutes,
                               Integer overtimeMinutes,
                               String leaveType,
                               String remark) {

    public static final int STATUS_NORMAL = 1;
    public static final int STATUS_LATE = 2;
    public static final int STATUS_EARLY_LEAVE = 3;
    public static final int STATUS_LEAVE = 4;
    public static final int STATUS_ABSENT = 5;

    public boolean isLeave() {
        return attendanceStatus == STATUS_LEAVE;
    }

    public boolean isAbsent() {
        return attendanceStatus == STATUS_ABSENT;
    }

    public boolean isLate() {
        return attendanceStatus == STATUS_LATE;
    }

    public boolean isEarlyLeave() {
        return attendanceStatus == STATUS_EARLY_LEAVE;
    }

    /*
    对外输出的状态名：模型读 JSON 比读数字更不容易出错。
     */
    public String statusName() {
        return switch (attendanceStatus) {
            case STATUS_NORMAL -> "NORMAL";
            case STATUS_LATE -> "LATE";
            case STATUS_EARLY_LEAVE -> "EARLY_LEAVE";
            case STATUS_LEAVE -> "LEAVE";
            case STATUS_ABSENT -> "ABSENT";
            default -> "UNKNOWN";
        };
    }
}
