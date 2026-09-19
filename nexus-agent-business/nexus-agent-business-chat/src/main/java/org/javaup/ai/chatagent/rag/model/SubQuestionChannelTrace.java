package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubQuestionChannelTrace {

    private String channelName;

    private int recalledCount;

    private int acceptedCount;
}
