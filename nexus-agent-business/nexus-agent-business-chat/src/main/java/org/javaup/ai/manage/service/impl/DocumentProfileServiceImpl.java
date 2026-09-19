package org.javaup.ai.manage.service.impl;

import lombok.AllArgsConstructor;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.data.SuperAgentDocumentProfile;
import org.javaup.ai.manage.data.SuperAgentDocumentStructureNode;
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentProfileMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentStructureNodeMapper;
import org.javaup.ai.manage.service.DocumentProfileService;
import org.javaup.ai.manage.service.DocumentStorageService;
import org.javaup.ai.manage.support.DocumentAnalysisResult;
import org.javaup.enums.BusinessStatus;
import org.javaup.enums.DocumentStructureNodeTypeEnum;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@AllArgsConstructor
@Service
public class DocumentProfileServiceImpl implements DocumentProfileService {

    private static final int PROFILE_STATUS_SUCCESS = 2;

    private final SuperAgentDocumentMapper documentMapper;
    private final SuperAgentDocumentProfileMapper documentProfileMapper;
    private final SuperAgentDocumentStructureNodeMapper structureNodeMapper;
    private final DocumentStorageService storageService;
    private final UidGenerator uidGenerator;













    /*
    文档画像生成
    导航产物同步完之后，紧接着就是 documentProfileService.generateProfile()。
    这个方法的目标不是解析正文，也不是做切块，而是把解析阶段已经产出的结构化事实压缩成一份更轻量的"画像快照"，供检索路由和知识管理界面使用。
     */
    /**
     * 为指定文档生成或更新“文档画像”。
     * <p>
     * 这个方法的目标不是解析正文，也不是做切块，而是把“解析阶段已经产出的结构化事实”
     * 压缩成一份更轻量、更适合检索路由和知识管理界面使用的画像快照。
     * 画像里主要包含几类信息：
     * 1. 文档摘要：帮助后台和检索侧快速理解这份文档大致讲什么；
     * 2. 文档类型：例如 faq、manual、rule、troubleshooting 等，用于后续策略判断；
     * 3. 核心主题与示例问题：帮助推荐可能的问法，也便于知识运营侧快速浏览；
     * 4. 图谱友好性、是否支持目录大纲、是否支持条目定位：用于判断是否适合图谱/导航能力；
     * 5. 知识范围、业务分类、标签：用于知识归档、筛选和后续召回范围控制。
     * </p>
     * <p>
     * 这一步与上传后的异步解析链关系非常紧密：
     * 上游已经拿到了 {@link DocumentAnalysisResult} 和结构节点列表；
     * 当前方法负责把这些信息进一步抽象成“画像”并落库。
     * 同时，它还会尝试把画像里推断出的知识范围、业务分类、标签等元数据
     * 回填到文档主表中，但只会补空，不会覆盖用户已经显式填写过的值。
     * </p>
     * <p>
     * 执行顺序上分成四段：
     * 1. 校验 documentId，并读取文档主记录；
     * 2. 基于 parsedText 与 structureNodes 生成一份临时 draft；
     * 3. 查询是否已有画像记录，如果有则做版本递增更新，没有则新建；
     * 4. 把 draft 中适合回填的文档元数据补回文档主表。
     * </p>
     */
    /*
    整个方法的执行逻辑分成四段：

1.校验 + 读取文档主记录：确认文档存在
2.构建画像草稿（buildDraft）：基于解析文本和结构节点，推断出文档类型、核心主题、示例问题、摘要、知识范围、业务分类、标签等
3.落库：查询是否已有画像记录，有则版本递增更新，没有则新建
4.回填文档元数据（backfillDocumentMetadata）：把画像推断出的知识范围、业务分类、标签等补回文档主表，但只做"补空"不做"覆盖"——如果用户已经显式填过，就保留用户值
其中 buildDraft() 是真正的"画像推断中心"：
     */
    @Override
    public SuperAgentDocumentProfile generateProfile(Long documentId,
                                                     DocumentAnalysisResult analysisResult,
                                                     List<SuperAgentDocumentStructureNode> structureNodes) {
        if (documentId == null) {
            throw new IllegalArgumentException("documentId 不能为空");
        }
        // 画像始终依附于真实存在的文档主记录，因此第一步必须确认文档存在。
        SuperAgentDocument document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new IllegalArgumentException("文档不存在: " + documentId);
        }
        // analysisResult 与 structureNodes 在某些补录/重建场景下可能为空，这里统一兜底成安全输入。
        String parsedText = analysisResult == null ? "" : StrUtil.blankToDefault(analysisResult.getParsedText(), "");
        List<SuperAgentDocumentStructureNode> safeNodes = structureNodes == null ? List.of() : structureNodes;
        // draft 是“画像生成过程中的中间态”，先把所有推断结果一次性算出来，后面再决定如何落库与回填。
        DocumentProfileDraft draft = buildDraft(document, parsedText, safeNodes);

        // 一份文档只维护一条有效画像记录；如果已经存在，则在原记录上递增画像版本。
        SuperAgentDocumentProfile profile = documentProfileMapper.selectOne(new LambdaQueryWrapper<SuperAgentDocumentProfile>()
            .eq(SuperAgentDocumentProfile::getDocumentId, documentId)
            .eq(SuperAgentDocumentProfile::getStatus, BusinessStatus.YES.getCode())
            .last("LIMIT 1"));
        boolean creating = profile == null;
        if (creating) {
            // 首次生成画像时创建新记录。
            profile = new SuperAgentDocumentProfile();
            profile.setId(uidGenerator.getUid());
            profile.setDocumentId(documentId);
            profile.setProfileVersion(1);
            profile.setStatus(BusinessStatus.YES.getCode());
        }
        else {
            // 重新生成画像时不新建记录，而是提升版本号，便于后台判断画像是否被重建过。
            profile.setProfileVersion(Optional.ofNullable(profile.getProfileVersion()).orElse(0) + 1);
        }
        // 以下字段都来自 draft，它们共同组成一份对外可消费的“文档能力摘要”。
        profile.setDocumentSummary(draft.documentSummary());
        profile.setDocumentType(draft.documentType());
        profile.setCoreTopics(joinJsonLikeArray(draft.coreTopics()));
        profile.setExampleQuestions(joinJsonLikeArray(draft.exampleQuestions()));
        profile.setGraphFriendly(draft.graphFriendly() ? 1 : 0);
        profile.setSupportsGraphOutline(draft.supportsGraphOutline() ? 1 : 0);
        profile.setSupportsItemLookup(draft.supportsItemLookup() ? 1 : 0);
        profile.setSupportsGraphAssist(draft.supportsGraphAssist() ? 1 : 0);
        profile.setProfileSource("auto");
        profile.setProfileStatus(PROFILE_STATUS_SUCCESS);
        profile.setErrorMsg(null);
        if (creating) {
            documentProfileMapper.insert(profile);
        }
        else {
            documentProfileMapper.updateById(profile);
        }

        // 画像生成出来后，顺手把可推断的知识范围、业务分类、标签等信息补回文档主表，
        // 这样后续列表页、检索配置和知识运营界面就不必只依赖画像表才能看到这些信息。
        backfillDocumentMetadata(document, draft);
        log.info("文档画像生成完成: documentId={}, documentType={}, graphFriendly={}, supportsItemLookup={}, scopeCode='{}', businessCategory='{}', tags='{}'",
            documentId,
            draft.documentType(),
            draft.graphFriendly(),
            draft.supportsItemLookup(),
            draft.knowledgeScopeCode(),
            draft.businessCategory(),
            draft.documentTags());
        return profile;
    }

    @Override
    public Optional<SuperAgentDocumentProfile> getByDocumentId(Long documentId) {
        if (documentId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(documentProfileMapper.selectOne(new LambdaQueryWrapper<SuperAgentDocumentProfile>()
            .eq(SuperAgentDocumentProfile::getDocumentId, documentId)
            .eq(SuperAgentDocumentProfile::getStatus, BusinessStatus.YES.getCode())
            .last("LIMIT 1")));
    }

    @Override
    public SuperAgentDocumentProfile regenerateProfile(Long documentId) {
        if (documentId == null) {
            throw new IllegalArgumentException("documentId 不能为空");
        }
        SuperAgentDocument document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new IllegalArgumentException("文档不存在: " + documentId);
        }
        String parsedText = StrUtil.isBlank(document.getParseTextPath()) ? "" : storageService.downloadText(document.getParseTextPath());
        List<SuperAgentDocumentStructureNode> structureNodes = structureNodeMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentStructureNode>()
            .eq(SuperAgentDocumentStructureNode::getDocumentId, documentId)
            .eq(SuperAgentDocumentStructureNode::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentDocumentStructureNode::getNodeNo, SuperAgentDocumentStructureNode::getId));
        DocumentAnalysisResult analysisResult = new DocumentAnalysisResult();
        analysisResult.setParsedText(parsedText);
        return generateProfile(documentId, analysisResult, structureNodes);
    }

    @Override
    public List<SuperAgentDocumentProfile> batchRegenerateProfiles(Collection<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return List.of();
        }
        List<SuperAgentDocumentProfile> profiles = new ArrayList<>();
        for (Long documentId : documentIds) {
            if (documentId == null) {
                continue;
            }
            profiles.add(regenerateProfile(documentId));
        }
        return profiles;
    }



    /*
    真正的画像推断中心
    构建文档画像草稿。
 * <p>
 * 这里是真正的“画像推断中心”，会把原始输入一步步归纳成最终画像字段。
 * 这些字段并不是彼此独立的，而是存在明显依赖关系：
 * 1. 先从结构节点中抽 section 标题；
 * 2. 再根据结构节点判断是否支持条目定位、是否支持图谱目录；
 * 3. 然后基于文档名、正文和标题推断 documentType；
 * 4. 再在 documentType 的基础上生成核心主题、示例问题、摘要、知识范围、业务分类和标签。
 * </p>
 * <p>
 * 之所以集中在一个 draft 里统一产出，是为了保证所有字段使用的是同一批输入快照，
 * 避免“摘要按旧数据生成、标签按新数据生成”这种不一致情况。
 * </p>
 buildDraft() 内部的推断链条是有依赖顺序的：先抽章节标题 → 再判断图谱能力 → 再推断文档类型 → 最后基于文档类型生成主题、问题、摘要、标签。之所以集中在一个 draft 里统一产出，是为了保证所有字段使用的是同一批输入快照，避免不一致。

     */
    private DocumentProfileDraft buildDraft(SuperAgentDocument document,
                                            String parsedText,
                                            List<SuperAgentDocumentStructureNode> structureNodes) {
        // sectionTitles 是后面多个推断逻辑的公共输入：
        // 摘要、核心主题、知识范围判断、图谱大纲判断都会用到。
        List<String> sectionTitles = extractSectionTitles(structureNodes);
        // 只要结构树里出现 step / list_item，就说明这份文档支持更细粒度的“条目定位式问答”。
        boolean supportsItemLookup = structureNodes.stream().anyMatch(node -> node != null
            && (DocumentStructureNodeTypeEnum.STEP.getCode().equals(node.getNodeType())
            || DocumentStructureNodeTypeEnum.LIST_ITEM.getCode().equals(node.getNodeType())));
        // section 标题足够多时，说明可以展示出较完整的目录型导航。
        boolean supportsGraphOutline = sectionTitles.size() >= 2;
        // 图谱友好性不是一个单独推断项，而是“支持大纲”或“支持条目定位”任一满足即可。
        boolean graphFriendly = supportsItemLookup || supportsGraphOutline;
        // 文档类型的判断会影响后面示例问题的问法和业务分类的归类方式。
        String documentType = inferDocumentType(document, parsedText, sectionTitles, supportsItemLookup);
        // coreTopics 会尽量优先取章节标题，再回退到文件名，目的是让主题更贴近用户可理解的领域词。
        List<String> coreTopics = buildCoreTopics(document, sectionTitles);
        // 示例问题并不是任意生成，而是依赖 documentType 对问句模板做差异化选择。
        List<String> exampleQuestions = buildExampleQuestions(documentType, coreTopics);
        String summary = buildSummary(document, sectionTitles, parsedText);
        String knowledgeScopeCode = inferKnowledgeScopeCode(document, sectionTitles, parsedText);
        String knowledgeScopeName = inferKnowledgeScopeName(knowledgeScopeCode);
        String businessCategory = inferBusinessCategory(documentType, parsedText);
        String documentTags = buildDocumentTags(document, knowledgeScopeCode, documentType, coreTopics);
        return new DocumentProfileDraft(
            summary,
            documentType,
            coreTopics,
            exampleQuestions,
            graphFriendly,
            supportsGraphOutline,
            supportsItemLookup,
            true,
            knowledgeScopeCode,
            knowledgeScopeName,
            businessCategory,
            documentTags
        );
    }


    /*
    画像落库之后，backfillDocumentMetadata() 会把推断出的知识范围、业务分类、标签等信息补回文档主表：
    将画像推断出的文档元数据回填到文档主表。
 * <p>
 * 注意这里只做"补空"而不做"覆盖"：
 * 如果文档主表中已经有用户显式维护过的知识范围、业务分类或标签，就保留用户值；
 * 只有对应字段为空时，才使用画像结果作为默认值补上。
 * </p>
 这个"补空不覆盖"的策略很重要——用户在知识管理界面手动填写的元数据优先级高于系统自动推断的结果。
     */
    private void backfillDocumentMetadata(SuperAgentDocument document, DocumentProfileDraft draft) {
        boolean changed = false;
        if (StrUtil.isBlank(document.getKnowledgeScopeCode()) && StrUtil.isNotBlank(draft.knowledgeScopeCode())) {
            document.setKnowledgeScopeCode(draft.knowledgeScopeCode());
            changed = true;
        }
        if (StrUtil.isBlank(document.getKnowledgeScopeName()) && StrUtil.isNotBlank(draft.knowledgeScopeName())) {
            document.setKnowledgeScopeName(draft.knowledgeScopeName());
            changed = true;
        }
        if (StrUtil.isBlank(document.getBusinessCategory()) && StrUtil.isNotBlank(draft.businessCategory())) {
            document.setBusinessCategory(draft.businessCategory());
            changed = true;
        }
        if (StrUtil.isBlank(document.getDocumentTags()) && StrUtil.isNotBlank(draft.documentTags())) {
            document.setDocumentTags(draft.documentTags());
            changed = true;
        }
        if (changed) {
            documentMapper.updateById(document);
        }
    }

    private List<String> extractSectionTitles(List<SuperAgentDocumentStructureNode> structureNodes) {
        if (CollUtil.isEmpty(structureNodes)) {
            return List.of();
        }
        return structureNodes.stream()
            .filter(node -> node != null && DocumentStructureNodeTypeEnum.SECTION.getCode().equals(node.getNodeType()))
            .map(SuperAgentDocumentStructureNode::getTitle)
            .filter(StrUtil::isNotBlank)
            .map(String::trim)
            .distinct()
            .limit(8)
            .toList();
    }


    /*
    文档类型推断用的是启发式关键词证据
    推断文档类型。
 * <p>
 * 这里采用启发式关键词规则，而不是复杂模型判断，目的是保持稳定、可解释、低成本。
 * </p>
 推断结果会综合文档名、原始文件名、章节标题和正文片段，避免只看正文导致误判。最终产出的文档类型（faq / troubleshooting / rule / spec / manual / intro）会影响后续示例问题的问法模板和业务分类的归类方式。
     */
    private String inferDocumentType(SuperAgentDocument document,
                                     String parsedText,
                                     List<String> sectionTitles,
                                     boolean supportsItemLookup) {
        String combined = combinedText(document, parsedText, sectionTitles);
        if (combined.contains("faq") || combined.contains("常见问题")) {
            return "faq";
        }
        if (combined.contains("故障") || combined.contains("排查") || combined.contains("检查顺序")) {
            return "troubleshooting";
        }
        if (combined.contains("规则") || combined.contains("制度")) {
            return "rule";
        }
        if (combined.contains("规格") || combined.contains("参数")) {
            return "spec";
        }
        if (supportsItemLookup || combined.contains("手册") || combined.contains("指南") || combined.contains("部署")) {
            return "manual";
        }
        return "intro";
    }

    private List<String> buildCoreTopics(SuperAgentDocument document, List<String> sectionTitles) {
        LinkedHashSet<String> topics = new LinkedHashSet<>();
        sectionTitles.stream().limit(6).forEach(title -> addTopic(topics, stripSectionCode(title)));
        addTopic(topics, stripFileExtension(document.getDocumentName()));
        return new ArrayList<>(topics).stream().filter(StrUtil::isNotBlank).limit(6).toList();
    }

    private void addTopic(Set<String> topics, String topic) {
        String normalized = StrUtil.blankToDefault(topic, "").trim();
        if (normalized.isBlank()) {
            return;
        }
        topics.add(normalized);
    }

    private List<String> buildExampleQuestions(String documentType, List<String> coreTopics) {
        List<String> examples = new ArrayList<>();
        for (String topic : coreTopics) {
            if ("troubleshooting".equals(documentType)) {
                examples.add(topic + "的可能原因有哪些？");
            }
            else if ("manual".equals(documentType)) {
                examples.add(topic + "的步骤是什么？");
            }
            else if ("rule".equals(documentType)) {
                examples.add(topic + "有哪些规则？");
            }
            else {
                examples.add(topic + "是什么意思？");
            }
        }
        return examples.stream().distinct().limit(6).toList();
    }

    private String buildSummary(SuperAgentDocument document, List<String> sectionTitles, String parsedText) {
        StringBuilder builder = new StringBuilder();
        builder.append("文档《").append(StrUtil.blankToDefault(document.getDocumentName(), "未命名文档")).append("》");
        if (!sectionTitles.isEmpty()) {
            builder.append("主要涵盖：").append(String.join("、", sectionTitles.stream().limit(4).toList())).append("。");
        }
        String excerpt = StrUtil.blankToDefault(parsedText, "").replaceAll("\\s+", " ").trim();
        if (excerpt.length() > 180) {
            excerpt = excerpt.substring(0, 180);
        }
        if (StrUtil.isNotBlank(excerpt)) {
            builder.append("摘要：").append(excerpt);
        }
        return builder.toString().trim();
    }

    private String inferKnowledgeScopeCode(SuperAgentDocument document,
                                           List<String> sectionTitles,
                                           String parsedText) {
        String combined = combinedText(document, parsedText, sectionTitles);
        if (containsAny(combined, "上线观察", "值班规则", "观察时长", "运营")) {
            return "operation_rule";
        }
        if (containsAny(combined, "机器人", "知识召回", "意图识别", "策略设计")) {
            return "robot_strategy";
        }
        if (containsAny(combined, "安装", "部署", "默认密码", "访问地址")) {
            return "deployment";
        }
        if (containsAny(combined, "故障", "排查", "异常", "检查顺序")) {
            return "troubleshooting";
        }
        if (containsAny(combined, "产品简介", "核心特性", "技术规格", "产品概述")) {
            return "product";
        }
        return "general_document";
    }

    private String inferKnowledgeScopeName(String scopeCode) {
        return switch (StrUtil.blankToDefault(scopeCode, "")) {
            case "operation_rule" -> "运营规则";
            case "robot_strategy" -> "机器人策略";
            case "deployment" -> "安装部署";
            case "troubleshooting" -> "故障排查";
            case "product" -> "产品资料";
            default -> "通用文档";
        };
    }

    private String inferBusinessCategory(String documentType, String parsedText) {
        if ("troubleshooting".equals(documentType)) {
            return "故障排查";
        }
        if ("rule".equals(documentType)) {
            return "规则";
        }
        if ("spec".equals(documentType)) {
            return "规格说明";
        }
        if ("manual".equals(documentType)) {
            return containsAny(parsedText.toLowerCase(Locale.ROOT), "步骤", "操作", "部署")
                ? "操作手册"
                : "手册";
        }
        return "介绍";
    }

    private String buildDocumentTags(SuperAgentDocument document,
                                     String knowledgeScopeCode,
                                     String documentType,
                                     List<String> coreTopics) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        if (StrUtil.isNotBlank(document.getDocumentTags())) {
            tags.addAll(List.of(document.getDocumentTags().split(",")));
        }
        addTag(tags, knowledgeScopeCode);
        addTag(tags, documentType);
        coreTopics.stream().limit(4).forEach(topic -> addTag(tags, topic));
        return tags.stream()
            .map(String::trim)
            .filter(StrUtil::isNotBlank)
            .distinct()
            .limit(8)
            .collect(Collectors.joining(","));
    }

    private void addTag(Set<String> tags, String tag) {
        String normalized = StrUtil.blankToDefault(tag, "").trim();
        if (normalized.isBlank()) {
            return;
        }
        tags.add(normalized);
    }

    private boolean containsAny(String text, String... values) {
        String normalized = StrUtil.blankToDefault(text, "").toLowerCase(Locale.ROOT);
        for (String value : values) {
            if (normalized.contains(StrUtil.blankToDefault(value, "").toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String combinedText(SuperAgentDocument document,
                                String parsedText,
                                List<String> sectionTitles) {
        return (StrUtil.blankToDefault(document.getDocumentName(), "") + " "
            + StrUtil.blankToDefault(document.getOriginalFileName(), "") + " "
            + String.join(" ", sectionTitles) + " "
            + StrUtil.blankToDefault(parsedText, ""))
            .toLowerCase(Locale.ROOT);
    }

    private String stripSectionCode(String title) {
        String normalized = StrUtil.blankToDefault(title, "").trim();
        return normalized.replaceFirst("^(第[一二三四五六七八九十百0-9]+[章节条部分]\\s*)|(\\d+(?:\\.\\d+)+\\s*)", "").trim();
    }

    private String stripFileExtension(String fileName) {
        String normalized = StrUtil.blankToDefault(fileName, "").trim();
        int index = normalized.lastIndexOf('.');
        return index > 0 ? normalized.substring(0, index) : normalized;
    }

    private String joinJsonLikeArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return values.stream()
            .map(value -> "\"" + value.replace("\"", "\\\"") + "\"")
            .collect(Collectors.joining(",", "[", "]"));
    }

    private record DocumentProfileDraft(
        String documentSummary,
        String documentType,
        List<String> coreTopics,
        List<String> exampleQuestions,
        boolean graphFriendly,
        boolean supportsGraphOutline,
        boolean supportsItemLookup,
        boolean supportsGraphAssist,
        String knowledgeScopeCode,
        String knowledgeScopeName,
        String businessCategory,
        String documentTags
    ) {
    }
}
