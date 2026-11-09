package org.javaup.ai.office.repository;

import jakarta.annotation.PostConstruct;
import org.javaup.ai.office.data.AttendanceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

/*
考勤明细的数据访问层。

为什么用 JdbcTemplate 而不是 MyBatis-Plus：office-tools 是独立演示服务，为一张表引一套 ORM 不划算，
这里真正需要的也只有"按工号 + 月份取一批行"这一条 SQL。

建表与演示数据统一放在 classpath:sql/nexus_agent_attendance_record.sql，启动时执行一次。
脚本是幂等的（CREATE TABLE IF NOT EXISTS + INSERT IGNORE），重复启动既不会报错也不会覆盖已有数据。
执行失败只 WARN，不阻塞 MCP server 启动——数据库没起来时，工具应该返回"数据源不可用"，
而不是让整个 server 起不来（和主链路 McpToolAssembler 的降级口径一致）。
 */
@Repository
public class AttendanceRecordRepository {

    private static final Logger log = LoggerFactory.getLogger(AttendanceRecordRepository.class);

    private static final String SCHEMA_SCRIPT = "sql/nexus_agent_attendance_record.sql";

    private static final String QUERY_MONTH_RECORDS = """
        SELECT employee_id, employee_name, attendance_date, attendance_status,
               clock_in_time, clock_out_time, work_minutes, overtime_minutes, leave_type, remark
        FROM nexus_agent_attendance_record
        WHERE employee_id = ? AND attendance_date >= ? AND attendance_date < ? AND status = 1
        ORDER BY attendance_date
        """;

    private static final RowMapper<AttendanceRecord> ROW_MAPPER = (resultSet, rowNum) -> new AttendanceRecord(
        resultSet.getString("employee_id"),
        resultSet.getString("employee_name"),
        resultSet.getDate("attendance_date").toLocalDate(),
        resultSet.getInt("attendance_status"),
        toLocalDateTime(resultSet.getTimestamp("clock_in_time")),
        toLocalDateTime(resultSet.getTimestamp("clock_out_time")),
        resultSet.getObject("work_minutes", Integer.class),
        resultSet.getObject("overtime_minutes", Integer.class),
        resultSet.getString("leave_type"),
        resultSet.getString("remark"));

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public AttendanceRecordRepository(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    /*
    幂等初始化：建表 + 补齐演示数据。
    用 EncodedResource 固定 UTF-8，避免 Windows 默认编码把脚本里的中文注释/姓名读坏。
     */
    @PostConstruct
    public void initSchema() {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                new EncodedResource(new ClassPathResource(SCHEMA_SCRIPT), StandardCharsets.UTF_8));
            log.info("nexus_agent_attendance_record 建表与演示数据初始化完成");
        } catch (Exception exception) {
            log.warn("nexus_agent_attendance_record 初始化失败，考勤查询将返回数据源不可用提示。"
                + "可手工执行 classpath:sql/nexus_agent_attendance_record.sql 排查", exception);
        }
    }

    /*
    按月取某个员工的考勤明细。
    用 [本月1日, 下月1日) 的半开区间而不是 DATE_FORMAT，能走 uk_attendance_employee_date 索引。
     */
    public List<AttendanceRecord> findByEmployeeAndMonth(String employeeId, YearMonth month) {
        LocalDate start = month.atDay(1);
        LocalDate nextMonthStart = month.plusMonths(1).atDay(1);
        return jdbcTemplate.query(QUERY_MONTH_RECORDS, ROW_MAPPER,
            employeeId, Date.valueOf(start), Date.valueOf(nextMonthStart));
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
