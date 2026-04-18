package org.javaup.ai.chatagent.tool;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.javaup.ai.chatagent.model.SearchReference;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 工具类
 * @author: 阿星不是程序员
 **/
/**
 * Tavily 搜索工具返回值。
 *
 * <p>这个对象既会回给 ReactAgent 参与下一轮推理，也会被业务层用于补充引用来源。
 * 这里继续保留 query / answer / results 三段结构，但改成普通 Java Bean。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TavilySearchToolResult {

    private String query;
    private String answer;
    private List<SearchReference> results;
}
