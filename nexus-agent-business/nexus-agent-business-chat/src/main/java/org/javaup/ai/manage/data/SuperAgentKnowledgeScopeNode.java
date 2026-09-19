package org.javaup.ai.manage.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.javaup.database.data.BaseTableData;


/*从数据库中查询所有启用状态的知识域节点。每个节点包含：

 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("nexus_agent_knowledge_scope_node")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentKnowledgeScopeNode extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String scopeCode;// 知识域编码（如 java_dev）

    private String scopeName;//知识域名称（如 "Java 开发"）

    private String parentScopeCode;

    private String description;//知识域描述

    private String aliases;//别名列表（如 "Java、JDK、Spring"）

    private String examples;//示例问题（如 "如何配置 Spring Boot"）

    private Integer sortOrder;
}
