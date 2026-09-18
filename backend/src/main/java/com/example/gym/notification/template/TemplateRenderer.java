package com.example.gym.notification.template;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class TemplateRenderer {

	private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.]+)\\s*}}");

	public String render(String template, Map<String, ?> variables) {

		if (template == null) {
			log.debug("TemplateRenderer: template is null");
			return null;
		}

		if (variables == null || variables.isEmpty()) {
			log.debug("TemplateRenderer: no variables supplied, returning original template. templateLength={}",
					template.length());
			return template;
		}

		log.debug("TemplateRenderer: rendering template. templateLength={}, variableKeys={}", template.length(),
				variables.keySet());

		Matcher matcher = VARIABLE.matcher(template);

		StringBuffer result = new StringBuffer();

		int variableCount = 0;
		int replacedCount = 0;
		int missingCount = 0;

		while (matcher.find()) {

			variableCount++;

			String key = matcher.group(1);

			Object value = variables.get(key);

			String replacement;

			if (value == null) {

				missingCount++;

				replacement = "";

				log.warn("TemplateRenderer: variable '{}' was not provided. Replacing with empty string.", key);

			} else {

				replacedCount++;

				replacement = String.valueOf(value);

				log.debug("TemplateRenderer: variable '{}' resolved. valueType={}", key,
						value.getClass().getSimpleName());
			}

			matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}

		matcher.appendTail(result);

		String rendered = result.toString();

		log.debug("TemplateRenderer: rendering completed. variablesFound={}, replaced={}, missing={}, resultLength={}",
				variableCount, replacedCount, missingCount, rendered.length());

		if (missingCount > 0) {
			log.warn("TemplateRenderer: {} template variable(s) were missing.", missingCount);
		}

		return rendered;
	}
}