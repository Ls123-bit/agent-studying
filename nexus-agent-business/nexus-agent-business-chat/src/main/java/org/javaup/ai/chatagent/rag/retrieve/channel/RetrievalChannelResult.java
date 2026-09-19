package org.javaup.ai.chatagent.rag.retrieve.channel;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.document.Document;

import java.util.List;


@Data
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalChannelResult {

    private String channelName;

    private List<Document> documents;
}
