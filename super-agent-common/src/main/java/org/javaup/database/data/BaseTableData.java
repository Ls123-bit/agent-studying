package org.javaup.database.data;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.util.Date;

/**
 * 数据库实体公共字段基类。
 *
 * <p>适合给需要统一审计字段的表实体继承，
 * 比如创建时间、更新时间、逻辑删除状态等。</p>
 */
@Data
public class BaseTableData {

    /**
     * 创建时间。
     *
     * <p>由 MyBatis-Plus 在 insert 时自动填充。</p>
     */
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    /**
     * 更新时间。
     *
     * <p>在 insert / update 时都会自动刷新。</p>
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date editTime;

    /**
     * 业务状态，通常 1 表示正常，0 表示删除或失效。
     */
    private Integer status;
}
