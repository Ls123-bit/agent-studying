package org.javaup.config;

import org.javaup.util.DateUtils;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 配置类
 * @author: 阿星不是程序员
 **/
/**
 * Date 反序列化器。
 *
 * <p>负责把接口里常见的日期字符串或时间戳统一转成 java.util.Date。</p>
 *
 * <p>当前支持的输入形式包括：</p>
 * <p>1. 毫秒时间戳字符串，例如 "1711440000000"</p>
 * <p>2. yyyy-MM</p>
 * <p>3. yyyy-MM-dd</p>
 * <p>4. yyyy-MM-dd HH:mm</p>
 * <p>5. yyyy-MM-dd HH:mm:ss</p>
 * <p>6. yyyy/MM/dd HH:mm:ss</p>
 *
 * <p>另外这里把 parseDate(String) 抽成了静态方法，
 * 让 InstantJsonDeserializer 也能复用同一套解析规则，
 * 避免 Date 和 Instant 的输入格式支持出现分叉。</p>
 */
public class DateJsonDeserializer extends JsonDeserializer<Date> {

	private static final Pattern P = Pattern.compile("^[0-9]*");
	private static final List<String> FORMAT = new ArrayList<>(4);

	static {
		FORMAT.add("yyyy-MM");
		FORMAT.add("yyyy-MM-dd");
		FORMAT.add("yyyy-MM-dd HH:mm");
		FORMAT.add("yyyy-MM-dd HH:mm:ss");
		FORMAT.add("yyyy/MM/dd HH:mm:ss");
	}

	@Override
	public Date deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JsonProcessingException {
		return parseDate(p.getText());
	}

	/**
	 * 解析日期字符串。
	 *
	 * <p>这里之所以做成静态方法，是为了让其他时间类型也能复用这套“字符串 -> 时间点”
	 * 的规则，而不是在多个反序列化器里重复维护一份。</p>
	 */
	public static Date parseDate(String str) {
		Date convertDate = null;

		if (str == null || "".equals(str)) {
			return null;
		}

		/*
		 * 先识别纯数字时间戳。
		 * 这对于“前端把毫秒值以字符串形式传过来”的场景比较有用。
		 */
		if (isNum(str)) {
			convertDate = DateUtils.parse(Long.valueOf(str));
		}
		else {
			if (str.matches("^\\d{4}-\\d{1,2}$")) {
				return DateUtils.parse(str, FORMAT.get(0));
			}
			else if (str.matches("^\\d{4}-\\d{1,2}-\\d{1,2}$")) {
				return DateUtils.parse(str, FORMAT.get(1));
			}
			else if (str.matches("^\\d{4}-\\d{1,2}-\\d{1,2} {1}\\d{1,2}:\\d{1,2}$")) {
				return DateUtils.parse(str, FORMAT.get(2));
			}
			else if (str.matches("^\\d{4}-\\d{1,2}-\\d{1,2} {1}\\d{1,2}:\\d{1,2}:\\d{1,2}$")) {
				return DateUtils.parse(str, FORMAT.get(3));
			}
			else if (str.matches("^\\d{4}/\\d{1,2}/\\d{1,2} {1}\\d{1,2}:\\d{1,2}:\\d{1,2}$")) {
				return DateUtils.parse(str, FORMAT.get(4));
			}
			else {
				throw new IllegalArgumentException("Invalid boolean value '" + str + "'");
			}
		}

		return convertDate;
	}

	/**
	 * 判断字符串是否为纯数字。
	 *
	 * <p>这里主要是为了识别“时间戳字符串”场景。</p>
	 */
	public static boolean isNum(String number) {
		Matcher m = P.matcher(number);
		return m.matches();
	}
}
