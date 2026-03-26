package org.javaup.database.mybatisplus;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.javaup.util.DateUtils;

import java.util.Date;
/**
 * MyBatis-Plus 字段自动填充处理器。
 *
 * <p>负责在数据库 insert / update 时自动写入公共审计字段，
 * 避免每个业务模块都手动设置 createTime / editTime。</p>
 */
@Slf4j
public class MybatisPlusMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        /*
         * 新增时同时填充创建时间和更新时间。
         */
        this.strictInsertFill(metaObject, "createTime", DateUtils::now, Date.class);
        this.strictInsertFill(metaObject, "editTime", DateUtils::now, Date.class);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        /*
         * 更新时只刷新 editTime。
         */
        this.strictUpdateFill(metaObject, "editTime", DateUtils::now, Date.class);
    }
}
