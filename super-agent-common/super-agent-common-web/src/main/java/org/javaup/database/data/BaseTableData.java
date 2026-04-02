package org.javaup.database.data;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.util.Date;

/**
 * 数据库实体公共字段基类。
 *
 * <p>适合给需要统一审计字段的表实体继承，
 * 比如创建时间、更新时间、通用业务状态等。</p>
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
     * 通用业务状态字段，通常 1 表示正常，0 表示失效或停用。
     *
     * <p>当前项目不再把它作为 MyBatis-Plus 的全局逻辑删除字段，
     * 删除数据时默认走物理删除；这个字段保留给业务侧自行定义状态语义。</p>
     */
    private Integer status;
}
