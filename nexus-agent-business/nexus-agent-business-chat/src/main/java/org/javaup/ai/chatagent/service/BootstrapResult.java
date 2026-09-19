package org.javaup.ai.chatagent.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import reactor.core.publisher.Flux;



/*
引导阶段的结果，要么成功（带 Flux），要么被拒绝（带原因）。
 */
@Data
@AllArgsConstructor
public class BootstrapResult {

    private final Flux<String> outbound;

    private final String rejectionMessage;

    public static BootstrapResult ready(Flux<String> outbound) {//引导成功，返回可用的输出流
        return new BootstrapResult(outbound, "");
    }

    public static BootstrapResult rejected(String rejectionMessage) {//引导失败，返回拒绝原因
        return new BootstrapResult(Flux.empty(), rejectionMessage);
    }
}
