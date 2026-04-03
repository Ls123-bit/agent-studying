package org.javaup.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 文档列表单条记录出参。
 *
 * <p>这个对象聚合了管理台列表页最常用的信息，
 * 让前端拿到文档后可以直接渲染状态、显示最近任务，并继续发起下一步操作。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentListItemVo {

    private Long documentId;

    private String documentName;

    private String originalFileName;

    private Integer fileType;

    private String fileTypeName;

    private Long fileSize;

    private Integer charCount;

    private Integer tokenCount;

    private Integer parseStatus;

    private String parseStatusName;

    private Integer strategyStatus;

    private String strategyStatusName;

    private Integer indexStatus;

    private String indexStatusName;

    private String parseErrorMsg;

    private String knowledgeScopeCode;

    private String knowledgeScopeName;

    private String businessCategory;

    private String documentTags;

    private Long currentPlanId;

    private Long lastIndexTaskId;

    private Long latestTaskId;

    private Integer latestTaskType;

    private String latestTaskTypeName;

    private Integer latestTaskStatus;

    private String latestTaskStatusName;

    private Date createTime;

    private Date editTime;
}
