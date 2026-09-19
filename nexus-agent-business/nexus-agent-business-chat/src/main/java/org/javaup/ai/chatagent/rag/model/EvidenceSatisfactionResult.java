package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceSatisfactionResult<T> {

    private boolean satisfied;

    @Builder.Default
    private List<String> notes = new ArrayList<>();

    @Builder.Default
    private List<T> acceptedEvidence = new ArrayList<>();
}
