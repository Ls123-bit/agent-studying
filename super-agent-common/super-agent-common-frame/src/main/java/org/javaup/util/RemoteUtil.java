package org.javaup.util;

import org.apache.commons.lang.StringUtils;

import jakarta.servlet.http.HttpServletRequest;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 工具类
 * @author: 阿星不是程序员
 **/
/**
 * 请求来源相关工具。
 *
 * <p>这里目前主要用于生成一个“远端标识”，
 * 通常由客户端 IP 和 User-Agent 组合而成。</p>
 */
public class RemoteUtil {

    /**
     * 生成请求来源标识。
     *
     * <p>优先使用反向代理透传的 {@code X-Forwarded-For}，
     * 如果没有再退回到 servlet 容器看到的 remote address。</p>
     */
    public static String getRemoteId(HttpServletRequest request) {
        String forward = request.getHeader("X-Forwarded-For");
        String ip = getRemoteIpFromForward(forward);
        String ua = request.getHeader("user-agent");
        if (StringUtils.isNotBlank(ip)) {
            return ip + ua;
        }
        return request.getRemoteAddr() + ua;
    }

    /**
     * 从代理头里取出最原始的客户端 IP。
     *
     * <p>{@code X-Forwarded-For} 可能是一个逗号分隔链路，通常第一个值才是真实来源 IP。</p>
     */
    private static String getRemoteIpFromForward(String forward) {
        if (StringUtils.isNotBlank(forward)) {
            String[] ipList = forward.split(",");
            return StringUtils.trim(ipList[0]);
        }
        return null;
    }
}
