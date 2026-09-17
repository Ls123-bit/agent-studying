package org.javaup.ai.chatagent.support;

import reactor.core.publisher.Sinks;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 支撑组件
 * @author: 阿星不是程序员
 **/


/*
线程安全的 Sink 发射工具，用 synchronized 保证串行发射。
线程安全发送
事件格式化好之后，还需要安全地推送到 Sink 里。SinkEmitHelper 就是干这个的——它封装了 Reactor Sink 的线程安全发送逻辑
 */
public class SinkEmitHelper {
    // SinkEmitHelper.java —— Reactor Sink 线程安全发送
    private SinkEmitHelper() {
    }

    // 向 Sink 安全地发送一条事件
    public static void emitNext(Sinks.Many<String> sink, String payload) {//安全发射一个值，CANCELLED/TERMINATED 时静默忽略

        if (sink == null || payload == null) {
            return;
        }

        // synchronized 保证同一时刻只有一个线程在向 Sink 写入
        synchronized (sink) {
            Sinks.EmitResult result = sink.tryEmitNext(payload);

            // 这三种失败是"正常的结束态"，不需要抛异常
            if (result == Sinks.EmitResult.FAIL_CANCELLED // Sink 已取消
                || result == Sinks.EmitResult.FAIL_TERMINATED// Sink 已终止
                || result == Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {// 没有订阅者
                return;
            }
            // 其他失败才是真正的异常
            if (result.isFailure()) {
                throw new IllegalStateException("流式事件发送失败: " + result);
            }
        }
    }


    // 安全关闭 Sink，通知前端流已结束
    public static void emitComplete(Sinks.Many<String> sink) {//安全发射完成信号
        if (sink == null) {
            return;
        }
        /*
        为什么需要 synchronized？
Reactor 的 Sinks.Many 在多线程并发调用 tryEmitNext 时可能返回 FAIL_NON_SERIALIZED，
意思是"有另一个线程正在写入"。虽然 Reactor 提供了重试机制（emitNext 带 EmitFailureHandler），
但最简单可靠的方式就是加 synchronized，保证同一时刻只有一个线程在操作 Sink。在聊天场景下，模型输出、思考步骤、状态通知可能从不同线程推送，所以这个锁是必要的。
         */
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
