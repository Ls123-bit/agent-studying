package org.javaup.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.Instant;
import java.util.Date;

/**
 * Instant 反序列化器。
 *
 * <p>Instant 和 Date 本质上都表示“时间点”，
 * 因此这里直接复用 DateJsonDeserializer 的解析逻辑：</p>
 * <p>先把字符串 / 时间戳解析成 Date，再把 Date 转成 Instant。</p>
 *
 * <p>这样做的好处是：</p>
 * <p>1. Date 和 Instant 在接口层支持同样的输入格式；</p>
 * <p>2. 以后如果 parseDate 新增格式支持，Instant 会自动同步受益。</p>
 */
public class InstantJsonDeserializer extends JsonDeserializer<Instant> {

    @Override
    public Instant deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JsonProcessingException {
        Date convertDate = DateJsonDeserializer.parseDate(p.getText());
        return convertDate != null ? convertDate.toInstant() : null;
    }
}
