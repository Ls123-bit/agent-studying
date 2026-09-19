package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


/*
buildChannelTraces 方法详解
先看一下 SubQuestionChannelTrace 这个数据结构，它就是一个简单的记录对象：
...
接下来看 buildChannelTraces 是怎么构建它的
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubQuestionChannelTrace {

    private String channelName;//通道名   eg："vector"、"keyword"

    private int recalledCount;//原始召回数 通道原始召回数量（闸门前）

    private int acceptedCount;//闸门通过数  闸门过滤后的采纳数量
}

/*
有了这个 trace，前端调试页面就能展示类似这样的信息：
向量通道：召回 10 条 → 闸门后保留 6 条
关键词通道：召回 8 条 → 闸门后保留 5 条
 */