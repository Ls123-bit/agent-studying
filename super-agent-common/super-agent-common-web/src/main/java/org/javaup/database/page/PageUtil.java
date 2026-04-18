package org.javaup.database.page;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.pagehelper.PageInfo;
import org.javaup.database.dto.BasePageDto;

import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 工具类
 * @author: 阿星不是程序员
 **/
/**
 * 分页工具。
 *
 * <p>负责在不同分页实现之间做统一转换：</p>
 * <p>1. 从入参 DTO 生成 MyBatis-Plus 的 {@link IPage}</p>
 * <p>2. 把 PageHelper / MyBatis-Plus 的分页结果转换成统一的 {@link PageVo}</p>
 */
public class PageUtil {

    /**
     * 从基础分页 DTO 创建分页参数。
     */
    public static <T> IPage<T> getPageParams(BasePageDto basePageDto) {
        return getPageParams(basePageDto.getPageNumber(), basePageDto.getPageSize());
    }

    /**
     * 直接按页码、页大小创建分页参数。
     */
    public static <T> IPage<T> getPageParams(int pageNumber, int pageSize) {
        return new Page<>(pageNumber, pageSize);
    }

    /**
     * 把 PageHelper 的分页结果转换成统一 PageVo，并支持元素映射。
     */
    public static <OLD,NEW> PageVo<NEW> convertPage(PageInfo<OLD> pageInfo, Function<? super OLD, ? extends NEW> function){
        return new PageVo<>(pageInfo.getPageNum(),
                pageInfo.getPageSize(),
                pageInfo.getTotal(),
                pageInfo.getList().stream().map(function).collect(Collectors.toList()));
    }

    /**
     * 把 MyBatis-Plus 的分页结果转换成统一 PageVo，并支持元素映射。
     */
    public static <OLD,NEW> PageVo<NEW> convertPage(IPage<OLD> iPage, Function<? super OLD, ? extends NEW> function){
        return new PageVo<>(iPage.getCurrent(),
                iPage.getSize(),
                iPage.getTotal(),
                iPage.getRecords().stream().map(function).collect(Collectors.toList()));
    }
}
