package org.javaup.ai.manage.model.route;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TopicRouteCandidate {

    private String topicCode;//Topic 编码

    private String topicName;//Topic 名称

    private String scopeCode;//所属Scope

    private BigDecimal score;//综合分数

    private String reason;//评分原因
}
