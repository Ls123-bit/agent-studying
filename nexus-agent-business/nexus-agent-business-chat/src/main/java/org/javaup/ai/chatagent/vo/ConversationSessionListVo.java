package org.javaup.ai.chatagent.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.javaup.ai.chatagent.model.ConversationSessionView;

import java.util.List;


@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationSessionListVo {

    private long pageNo;

    private long pageSize;

    private long totalSize;

    private long totalPages;

    private List<ConversationSessionView> sessions;
}
