package com.example.gym.notification.template;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class TemplateRenderer {

	private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.]+)\\s*}}");

	public String render(String template, Map<String, ?> variables) {

		if (template == null) {
			return null;
		}

		if (variables == null || variables.isEmpty()) {
			return template;
		}

		Matcher matcher = VARIABLE.matcher(template);

		StringBuffer result = new StringBuffer();

		while (matcher.find()) {

			String key = matcher.group(1);

			Object value = variables.get(key);

			String replacement = value == null ? "" : String.valueOf(value);

			matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}

		matcher.appendTail(result);

		return result.toString();
	}
}