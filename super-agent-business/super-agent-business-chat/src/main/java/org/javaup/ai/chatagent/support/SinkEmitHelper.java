package org.javaup.ai.chatagent.support;

import reactor.core.publisher.Sinks;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 支撑组件
 * @author: 阿星不是程序员
 **/
public class SinkEmitHelper {

    private SinkEmitHelper() {
    }

    public static void emitNext(Sinks.Many<String> sink, String payload) {
        /*
         * 先做最轻量的空值防御，避免上层在收尾阶段重复调用时抛出无意义异常。
         */
        if (sink == null || payload == null) {
            return;
        }

        /*
         * 同一个 sink 可能同时被模型流、工具 thinking 和 stop/finish 逻辑访问，
         * 这里通过 synchronized 保证发送和关闭之间不会互相打架。
         */
        synchronized (sink) {
            Sinks.EmitResult result = sink.tryEmitNext(payload);

            /*
             * 这些结果说明 sink 已经没人接收或已经结束，属于可安全忽略的终态。
             */
            if (result == Sinks.EmitResult.FAIL_CANCELLED
                || result == Sinks.EmitResult.FAIL_TERMINATED
                || result == Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
                return;
            }
            if (result.isFailure()) {
                throw new IllegalStateException("流式事件发送失败: " + result);
            }
        }
    }

    public static void emitComplete(Sinks.Many<String> sink) {
        if (sink == null) {
            return;
        }
        synchronized (sink) {
            Sinks.EmitResult result = sink.tryEmitComplete();
            if (result == Sinks.EmitResult.FAIL_CANCELLED
                || result == Sinks.EmitResult.FAIL_TERMINATED
                || result == Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
                return;
            }
            if (result.isFailure()) {
                throw new IllegalStateException("流式事件关闭失败: " + result);
            }
        }
    }
}
