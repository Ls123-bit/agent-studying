package org.javaup.ai.manage.service;

import org.javaup.ai.manage.data.SuperAgentDocumentProfile;
import org.javaup.ai.manage.data.SuperAgentDocumentStructureNode;
import org.javaup.ai.manage.support.DocumentAnalysisResult;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DocumentProfileService {

    SuperAgentDocumentProfile generateProfile(Long documentId,
                                              DocumentAnalysisResult analysisResult,
                                              List<SuperAgentDocumentStructureNode> structureNodes);

    SuperAgentDocumentProfile regenerateProfile(Long documentId);

    List<SuperAgentDocumentProfile> batchRegenerateProfiles(Collection<Long> documentIds);

    Optional<SuperAgentDocumentProfile> getByDocumentId(Long documentId);
}
