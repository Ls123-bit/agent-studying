package org.javaup.ai.chatagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.javaup.ai.chatagent.data.GraphCheckpoint;

/**
 * ReactAgent Graph checkpoint 表 Mapper。
 */
@Mapper
public interface GraphCheckpointMapper extends BaseMapper<GraphCheckpoint> {
}
