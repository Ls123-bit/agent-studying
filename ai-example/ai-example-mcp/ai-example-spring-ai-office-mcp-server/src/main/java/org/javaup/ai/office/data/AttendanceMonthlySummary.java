package org.javaup.ai.office.data;

import java.util.List;

/*
月度考勤聚合结果。

字段口径：
- workDays：该月有考勤记录的天数（本演示不接节假日日历，记录数即应出勤天数）；
- actualDays：实际出勤天数 = 记录数 - 请假 - 缺勤（迟到/早退仍算出勤）；
- overtimeHours：加班分钟合计换算成小时，保留一位小数。
 */
public record AttendanceMonthlySummary(int workDays,
                                       int actualDays,
                                       int lateTimes,
                                       int earlyLeaveTimes,
                                       int leaveDays,
                                       int absentDays,
                                       double overtimeHours) {

    public static AttendanceMonthlySummary of(List<AttendanceRecord> records) {
        int lateTimes = 0;
        int earlyLeaveTimes = 0;
        int leaveDays = 0;
        int absentDays = 0;
        int overtimeMinutes = 0;
        for (AttendanceRecord record : records) {
            if (record.isLeave()) {
                leaveDays++;
            } else if (record.isAbsent()) {
                absentDays++;
            }
            if (record.isLate()) {
                lateTimes++;
            }
            if (record.isEarlyLeave()) {
                earlyLeaveTimes++;
            }
            overtimeMinutes += record.overtimeMinutes() == null ? 0 : record.overtimeMinutes();
        }
        int workDays = records.size();
        return new AttendanceMonthlySummary(workDays, workDays - leaveDays - absentDays,
            lateTimes, earlyLeaveTimes, leaveDays, absentDays,
            Math.round(overtimeMinutes / 60.0 * 10) / 10.0);
    }
}
