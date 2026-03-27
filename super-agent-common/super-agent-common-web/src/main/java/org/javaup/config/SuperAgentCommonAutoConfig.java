package org.javaup.config;

import java.util.List;

import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * common 模块的自动配置入口。
 *
 * <p>这里把 Jackson 能力拆成两层，目的就是把“普通接口展示友好”和“内部 JSON
 * 语义正确”同时保住：</p>
 *
 * <p>1. 全局 ObjectMapper 只做安全的通用配置，比如日期格式化。</p>
 * <p>2. MVC 专用 ObjectMapper 只给 Controller HTTP 出入参使用，
 * 在这一层额外叠加“数字转字符串”和“null 友好输出”。</p>
 *
 * <p>为什么要这样拆？因为 Spring 容器里的全局 ObjectMapper 不只会服务 MVC，
 * 还会被 RestClient/WebClient、第三方 SDK、消息序列化等内部链路复用。
 * 如果把“数字写成字符串”“空值强行改写为空串/空数组”直接挂在全局 ObjectMapper 上，
 * 发给外部系统的 JSON 也会被一起改坏。之前 AI 请求打到 DashScope 返回 500，
 * 本质上就是因为 max_tokens、temperature 这类字段被全局 Jackson 改成了字符串。</p>
 */
public class SuperAgentCommonAutoConfig {

    /**
     * 注册全局 Jackson 定制。
     *
     * <p>这一层只做“全应用都安全”的配置，不再承载前端展示层特有的策略。</p>
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustom() {
        return new JacksonCustom();
    }

    /**
     * 只在 Servlet MVC 场景下替换 Jackson 消息转换器。
     *
     * <p>这里不去直接修改 Spring 容器中的全局 ObjectMapper，
     * 而是基于它 copy 出一份“只给 HTTP Controller 用”的副本。
     * 这样普通接口还能保持前端友好的输出风格，
     * 但 AI 出站请求、内部 HTTP 调用、其他非 MVC JSON 序列化都不会受影响。</p>
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(WebMvcConfigurer.class)
    @ConditionalOnBean(ObjectMapper.class)
    public WebMvcConfigurer webMvcJacksonConfigurer(ObjectMapper objectMapper) {
        return new WebMvcConfigurer() {
            @Override
            public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
                /*
                 * MVC 层专用的 ObjectMapper：
                 * 先继承全局日期格式化等能力，再叠加前端友好的 null/数字处理策略。
                 */
                MappingJackson2HttpMessageConverter mvcJacksonConverter =
                    new MappingJackson2HttpMessageConverter(createMvcObjectMapper(objectMapper));

                for (int i = 0; i < converters.size(); i++) {
                    if (converters.get(i) instanceof MappingJackson2HttpMessageConverter existingConverter) {
                        /*
                         * 沿用原有 converter 的媒体类型支持范围，
                         * 避免替换后影响 application/json 之外的兼容性。
                         */
                        mvcJacksonConverter.setSupportedMediaTypes(existingConverter.getSupportedMediaTypes());
                        converters.set(i, mvcJacksonConverter);
                        return;
                    }
                }

                /*
                 * 理论上 Spring MVC 默认会有 Jackson converter。
                 * 这里做一个保底插入，避免某些极端定制场景下没有可替换目标。
                 */
                converters.add(0, mvcJacksonConverter);
            }
        };
    }

    /**
     * 创建一份只给 MVC 层用的 ObjectMapper 副本。
     *
     * <p>这个副本额外做两件事：</p>
     * <p>1. 为 null 值挂上自定义输出策略，尽量减少前端/app 因空值处理不稳导致的崩溃。</p>
     * <p>2. 打开 WRITE_NUMBERS_AS_STRINGS，降低大整数在 JS 端的精度丢失风险。</p>
     *
     * <p>注意：这些规则现在只在 HTTP 接口层生效，
     * 不会再反向污染 AI 请求或其他内部 JSON 序列化链路。</p>
     */
    private ObjectMapper createMvcObjectMapper(ObjectMapper objectMapper) {
        ObjectMapper mvcObjectMapper = objectMapper.copy();
        mvcObjectMapper.setSerializerFactory(
            mvcObjectMapper.getSerializerFactory().withSerializerModifier(new JsonCustomSerializer())
        );
        mvcObjectMapper.getFactory().configure(JsonWriteFeature.WRITE_NUMBERS_AS_STRINGS.mappedFeature(), true);
        return mvcObjectMapper;
    }
}
