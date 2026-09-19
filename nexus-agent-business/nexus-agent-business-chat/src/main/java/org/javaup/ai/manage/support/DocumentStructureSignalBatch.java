package org.javaup.ai.manage.support;

import java.util.List;


public record DocumentStructureSignalBatch(
    List<String> contextLines,
    List<DocumentStructureSignal> signals
) {
}
