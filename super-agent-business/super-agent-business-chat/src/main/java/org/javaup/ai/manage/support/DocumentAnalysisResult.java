package org.javaup.ai.manage.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 支撑组件
 * @author: 阿星不是程序员
 **/
/**
 * 文档解析与分析结果。
 *
 * <p>这个对象是“解析阶段”和“策略推荐阶段”之间最关键的桥接数据结构。</p>
 *
 * <p>可以把它理解成一份文档在进入切块推荐前的“体检报告”：</p>
 * <p>1. parsedText 提供后续真正参与切块的正文。</p>
 * <p>2. charCount / tokenCount 描述文档体量。</p>
 * <p>3. structureLevel / headingCount 描述文档结构化程度。</p>
 * <p>4. paragraphCount / maxParagraphLength 描述段落分布与超长风险。</p>
 * <p>5. contentQualityLevel 描述解析结果是否稳定、是否有较多噪声。</p>
 *
 * <p>策略推荐服务会基于这些字段判断：</p>
 * <p>1. 是否推荐结构切块。</p>
 * <p>2. 是否需要递归切块兜底。</p>
 * <p>3. 是否适合使用语义切块优化边界。</p>
 * <p>4. 是否需要大模型智能切块增强低质量场景。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentAnalysisResult {

    /**
     * 清洗后的正文文本。
     *
     * <p>这是后续真正参与切块和索引构建的文本主体。
     * 后面的结构切块、递归切块、语义切块都基于它执行，而不是基于原始二进制文件。</p>
     */
    private String parsedText;

    /**
     * 字符数。
     *
     * <p>用于衡量文档总体体量，主要影响：</p>
     * <p>1. 是否推荐递归切块。</p>
     * <p>2. 是否满足语义切块或 LLM 切块的最小文本长度条件。</p>
     */
    private Integer charCount;

    /**
     * 粗略 token 数。
     *
     * <p>主要用于页面展示和日志记录，帮助理解文档大概规模。
     * 当前策略推荐并不直接依赖这个值做严格判断，但它能帮助排查“为什么文档被认为偏长”。</p>
     */
    private Integer tokenCount;

    /**
     * 结构化程度。
     *
     * <p>这是一个综合判断结果，主要由标题数和段落分布推导而来。
     * 在推荐策略时，它是判断是否优先采用“基于文档结构切块”的核心字段之一。</p>
     */
    private Integer structureLevel;

    /**
     * 内容质量等级。
     *
     * <p>描述解析后的文本是否干净、稳定、适合直接用于检索和切块。
     * 当质量偏低时，后续会更倾向于推荐大模型智能切块来增强复杂场景处理能力。</p>
     */
    private Integer contentQualityLevel;

    /**
     * 识别出的标题数量。
     *
     * <p>这是结构程度判断的原始信号之一。
     * 即使 structureLevel 只是 MEDIUM，只要标题数达到一定数量，仍可能推荐结构切块。</p>
     */
    private Integer headingCount;

    /**
     * 段落数量。
     *
     * <p>段落数越多，越说明文本已经形成较稳定的内容块。
     * 语义切块推荐会要求段落数达到一个最小门槛，避免对过短文本做过度处理。</p>
     */
    private Integer paragraphCount;

    /**
     * 最长段落长度。
     *
     * <p>这是判断是否需要递归切块兜底的重要输入。
     * 因为有些文档总长度不算大，但单个段落非常长，仍然会导致 chunk 过大。</p>
     */
    private Integer maxParagraphLength;

    /**
     * 解析阶段抽取出的结构节点树。
     *
     * <p>它不是最终切块结果，而是后续结构导航和结构化切块的基础数据。</p>
     */
    private List<DocumentStructureNodeCandidate> structureNodes = new ArrayList<>();
}
