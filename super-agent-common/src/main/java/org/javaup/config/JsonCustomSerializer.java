package org.javaup.config;

import cn.hutool.core.date.DateTime;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * MVC 专用的 null 值输出策略。
 *
 * <p>这个类只负责一件事：当字段值为 null 时，该输出什么。</p>
 *
 * <p>它不会改写非 null 的真实值：</p>
 * <p>1. 非 null 数字是否输出成字符串，由 WRITE_NUMBERS_AS_STRINGS 决定。</p>
 * <p>2. 非 null 时间如何格式化，由 JacksonCustom 里注册的时间序列化器决定。</p>
 *
 * <p>设计目标是让普通接口返回的 JSON 更适合前端/app 直接消费，
 * 尽量减少 null 判空分支、空白渲染和客户端崩溃。</p>
 *
 * <p>注意：它现在只挂在 MVC 专用 ObjectMapper 上，
 * 不会再影响 AI 出站请求和其他内部序列化链路。</p>
 */
public class JsonCustomSerializer extends BeanSerializerModifier {

	@Override
	public List<BeanPropertyWriter> changeProperties(SerializationConfig config, BeanDescription beanDesc,
			List<BeanPropertyWriter> beanProperties) {
		/*
		 * 遍历每个字段，根据字段类型给 null 指定不同的输出策略。
		 */
		for (BeanPropertyWriter writer : beanProperties) {
			com.fasterxml.jackson.databind.JsonSerializer<Object> js = judgeType(writer);
			if (js != null) {
				writer.assignNullSerializer(js);
			}
		}
		return beanProperties;
	}

	/**
	 * 根据字段类型决定 null 值怎么写出。
	 */
	public com.fasterxml.jackson.databind.JsonSerializer<Object> judgeType(BeanPropertyWriter writer) {
		JavaType javaType = writer.getType();
		Class<?> clazz = javaType.getRawClass();

		/*
		 * String 为 null 时输出空串，减少前端直接渲染时报空。
		 */
		if (String.class.isAssignableFrom(clazz)) {
			return new com.fasterxml.jackson.databind.JsonSerializer<Object>() {
				@Override
				public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers)
						throws IOException {
					gen.writeString("");
				}
			};
		}

		/*
		 * Number 为 null 时也输出空串。
		 * 这和“真实数字要不要加引号”不是同一层概念：
		 * 真正的数字字符串化由 MVC ObjectMapper 上的 WRITE_NUMBERS_AS_STRINGS 负责。
		 */
		if (Number.class.isAssignableFrom(clazz)) {
			return new com.fasterxml.jackson.databind.JsonSerializer<Object>() {
				@Override
				public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers)
						throws IOException {
					gen.writeString("");
				}
			};
		}

		/*
		 * Boolean 为 null 时输出 false，减少客户端判空。
		 */
		if (Boolean.class.isAssignableFrom(clazz)) {
			return new com.fasterxml.jackson.databind.JsonSerializer<Object>() {
				@Override
				public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers)
						throws IOException {
					gen.writeBoolean(false);
				}
			};
		}

		/*
		 * 日期类为 null 时输出空串，符合大多数表单/表格展示习惯。
		 */
		if (java.util.Date.class.isAssignableFrom(clazz)) {
			return new com.fasterxml.jackson.databind.JsonSerializer<Object>() {
				@Override
				public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers)
						throws IOException {
					gen.writeString("");
				}
			};
		}

		/*
		 * Hutool DateTime 单独兼容。
		 */
		if (clazz.equals(DateTime.class)) {
			return new com.fasterxml.jackson.databind.JsonSerializer<Object>() {
				@Override
				public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers)
						throws IOException {
					gen.writeString("");
				}
			};
		}

		/*
		 * 数组/集合为 null 时统一输出 []，避免前端把 null 和空集合当成两种结构处理。
		 */
		if (clazz.isArray() || clazz.equals(List.class) || clazz.equals(Set.class)) {
			return new com.fasterxml.jackson.databind.JsonSerializer<Object>() {
				@Override
				public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers)
						throws IOException {
					gen.writeStartArray();
					gen.writeEndArray();
				}
			};
		}
		return null;
	}
}
