package org.javaup.ai.chatagent.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.javaup.database.data.BaseTableData;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 数据实体
 * @author: 阿星不是程序员
 **/
/**
 * 对话归档主表实体。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_chat_dialogue")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentChatDialogue extends BaseTableData {

    /**
     * 主键 id。
     */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /**
     * 对话唯一业务编号。
     */
    @TableField("dialogue_code")
    private String conversationId;

    /**
     * 对话业务阶段。
     */
    @TableField("dialogue_stage")
    private Integer sessionStatus;

    /**
     * 当前会话采用的显式提问模式。
     *
     * <p>这里存的是枚举 code。
     * 对教学项目来说，这个字段能直接把“用户选了哪条产品路径”保存在数据库里，
     * 后面查看会话列表、回放会话详情时都会更清楚。</p>
     */
    @TableField("chat_mode")
    private Integer chatMode;

    /**
     * 当前会话显式锁定的提问文档id。
     */
    @TableField("selected_document_id")
    private Long selectedDocumentId;

    /**
     * 当前会话显式锁定的提问文档名称。
     */
    @TableField("selected_document_name")
    private String selectedDocumentName;
}
