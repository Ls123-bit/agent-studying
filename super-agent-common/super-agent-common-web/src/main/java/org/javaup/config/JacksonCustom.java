package org.javaup.config;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.core.Ordered;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.TimeZone;

/**
 * 全局 Jackson 定制。
 *
 * <p>这里的边界非常重要：只放“对整个应用都安全”的规则。</p>
 *
 * <p>当前保留的是：</p>
 * <p>1. Date / LocalDateTime / LocalDate / LocalTime / Instant 的统一格式化与反序列化。</p>
 * <p>2. 少量历史 JSON 的宽松解析能力。</p>
 *
 * <p>这里不再放“数字转字符串”“null 改写为空串/空数组”这类前端展示层策略，
 * 因为全局 ObjectMapper 还会被 AI 请求、RestClient/WebClient、消息序列化等内部链路复用。
 * 如果把这些策略挂在这里，就会把发给外部系统的 JSON 也一起改坏。</p>
 */
public class JacksonCustom implements Jackson2ObjectMapperBuilderCustomizer, Ordered {

    /**
     * 默认日期时间格式。
     */
    private final String dateTimeFormat = "yyyy-MM-dd HH:mm:ss";

    @Override
    public void customize(Jackson2ObjectMapperBuilder builder) {
        /*
         * 默认输出所有字段，避免 null 字段被静默裁掉。
         * 注意这里只决定“字段是否出现”，不决定 null 具体写成什么。
         */
        builder.serializationInclusion(Include.ALWAYS);

        /*
         * 保留一些历史 JSON 的宽松解析能力。
         */
        builder.featuresToEnable(Feature.ALLOW_SINGLE_QUOTES);
        builder.featuresToEnable(Feature.ALLOW_UNQUOTED_FIELD_NAMES);

        /*
         * java.util.Date 的统一格式化规则。
         */
        builder.serializerByType(Date.class, new JsonSerializer<Date>() {

            @Override
            public void serialize(Date value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                SimpleDateFormat sdf = new SimpleDateFormat(dateTimeFormat);
                String newValue = sdf.format(value);
                gen.writeString(newValue);
            }

        });
        builder.deserializerByType(Date.class, new DateJsonDeserializer());

        /*
         * Java 8 时间类型的统一格式化规则。
         * 这些都是领域层通用格式，放到全局是安全的。
         */
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(dateTimeFormat);
        builder.serializerByType(LocalDateTime.class, new LocalDateTimeSerializer(dateTimeFormatter));
        builder.deserializerByType(LocalDateTime.class, new LocalDateTimeDeserializer(dateTimeFormatter));
        builder.serializerByType(Instant.class, new JsonSerializer<Instant>() {

            @Override
            public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                /*
                 * Instant 是绝对时间点，没有本地格式。
                 * 对外展示时统一转成系统时区下的 yyyy-MM-dd HH:mm:ss。
                 */
                String newValue = LocalDateTime.ofInstant(value, ZoneId.systemDefault()).format(dateTimeFormatter);
                gen.writeString(newValue);
            }

        });
        builder.deserializerByType(Instant.class, new InstantJsonDeserializer());

        String dateFormat = "yyyy-MM-dd";
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern(dateFormat);
        builder.serializerByType(LocalDate.class, new LocalDateSerializer(dateFormatter));
        builder.deserializerByType(LocalDate.class, new LocalDateDeserializer(dateFormatter));

        String timeFormat = "HH:mm:ss";
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern(timeFormat);
        builder.serializerByType(LocalTime.class, new LocalTimeSerializer(timeFormatter));
        builder.deserializerByType(LocalTime.class, new LocalTimeDeserializer(timeFormatter));

        /*
         * 跟随系统默认时区，避免 Date / Instant 输出文本时发生时区偏差。
         */
        builder.timeZone(TimeZone.getDefault());

        /*
         * 对未知字段宽容一些，减少接口灰度或前后端联调期间的失败率。
         */
        builder.featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        /*
         * 兼容部分未转义控制字符的历史输入。
         */
        builder.featuresToEnable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature());
    }

    @Override
    public int getOrder() {
        return 1;
    }
}
