package org.javaup.ai.manage.model.route;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScopeRouteCandidate {

    private String scopeCode;//Scope 编码

    private String scopeName;//Scope 名称

    private BigDecimal score;//综合分数

    private String reason;//评分原因
}
