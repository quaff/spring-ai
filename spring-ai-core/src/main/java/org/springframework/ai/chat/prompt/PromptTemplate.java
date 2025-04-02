/*
 * Copyright 2023-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.ai.chat.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.common.CompositeStringExpression;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.standard.SpelExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.DataBindingPropertyAccessor;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.Media;
import org.springframework.core.io.Resource;
import org.springframework.util.StreamUtils;

public class PromptTemplate implements PromptTemplateActions, PromptTemplateMessageActions {

	protected String template;

	protected TemplateFormat templateFormat = TemplateFormat.ST;

	private Expression expression;

	private EvaluationContext evaluationContext = SimpleEvaluationContext
			.forPropertyAccessors(new MapAccessor(false), DataBindingPropertyAccessor.forReadOnlyAccess()).build();

	private Map<String, Object> dynamicModel = new HashMap<>();

	public PromptTemplate(Resource resource) {
		try (InputStream inputStream = resource.getInputStream()) {
			this.template = StreamUtils.copyToString(inputStream, Charset.defaultCharset());
		}
		catch (IOException ex) {
			throw new RuntimeException("Failed to read resource", ex);
		}
		try {
			this.expression = parseExpression(this.template);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("The template string is not valid.", ex);
		}
	}

	public PromptTemplate(String template) {
		this.template = template;
		// If the template string is not valid, an exception will be thrown
		try {
			this.expression = parseExpression(this.template);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("The template string is not valid.", ex);
		}
	}

	public PromptTemplate(String template, Map<String, Object> model) {
		this.template = template;
		// If the template string is not valid, an exception will be thrown
		try {
			this.expression = parseExpression(this.template);
			for (Entry<String, Object> entry : model.entrySet()) {
				add(entry.getKey(), entry.getValue());
			}
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("The template string is not valid.", ex);
		}
	}

	public PromptTemplate(Resource resource, Map<String, Object> model) {
		try (InputStream inputStream = resource.getInputStream()) {
			this.template = StreamUtils.copyToString(inputStream, Charset.defaultCharset());
		}
		catch (IOException ex) {
			throw new RuntimeException("Failed to read resource", ex);
		}
		// If the template string is not valid, an exception will be thrown
		try {
			this.expression = parseExpression(this.template);
			for (Entry<String, Object> entry : model.entrySet()) {
				this.add(entry.getKey(), entry.getValue());
			}
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("The template string is not valid.", ex);
		}
	}

	public void add(String name, Object value) {
		this.dynamicModel.put(name, value);
	}

	public String getTemplate() {
		return this.template;
	}

	public TemplateFormat getTemplateFormat() {
		return this.templateFormat;
	}

	// Render Methods
	@Override
	public String render() {
		validate(this.dynamicModel);
		return (String) this.expression.getValue(this.evaluationContext, this.dynamicModel);
	}

	@Override
	public String render(Map<String, Object> model) {
		validate(model);
		Map<String, Object> variables = new LinkedHashMap<>(this.dynamicModel);
		for (Entry<String, Object> entry : model.entrySet()) {
			if (entry.getValue() instanceof Resource) {
				variables.put(entry.getKey(), renderResource((Resource) entry.getValue()));
			}
			else {
				variables.put(entry.getKey(), entry.getValue());
			}
		}
		return (String) this.expression.getValue(this.evaluationContext, variables);
	}

	private Expression parseExpression(String template) {
		SpelExpressionParser parser = new SpelExpressionParser();
		return parser.parseExpression(template, new TemplateParserContext("{", "}"));
	}

	private String renderResource(Resource resource) {
		try {
			return resource.getContentAsString(Charset.defaultCharset());
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public Message createMessage() {
		return new UserMessage(render());
	}

	@Override
	public Message createMessage(List<Media> mediaList) {
		return new UserMessage(render(), mediaList);
	}

	@Override
	public Message createMessage(Map<String, Object> model) {
		return new UserMessage(render(model));
	}

	@Override
	public Prompt create() {
		return new Prompt(render(new HashMap<>()));
	}

	@Override
	public Prompt create(ChatOptions modelOptions) {
		return new Prompt(render(new HashMap<>()), modelOptions);
	}

	@Override
	public Prompt create(Map<String, Object> model) {
		return new Prompt(render(model));
	}

	@Override
	public Prompt create(Map<String, Object> model, ChatOptions modelOptions) {
		return new Prompt(render(model), modelOptions);
	}

	public Set<String> getInputVariables() {
		Set<String> inputVariables = new HashSet<>();
		if (this.expression instanceof CompositeStringExpression cse) {
			for (Expression ex : cse.getExpressions()) {
				if (ex instanceof SpelExpression se) {
					inputVariables.add(se.getExpressionString());
				}
			}
		}

		return inputVariables;
	}

	private Set<String> getModelKeys(Map<String, Object> model) {
		Set<String> dynamicVariableNames = new HashSet<>(this.dynamicModel.keySet());
		Set<String> modelVariables = new HashSet<>(model.keySet());
		modelVariables.addAll(dynamicVariableNames);
		return modelVariables;
	}

	protected void validate(Map<String, Object> model) {

		Set<String> templateTokens = getInputVariables();
		Set<String> modelKeys = getModelKeys(model);

		// Check if model provides all keys required by the template
		if (!modelKeys.containsAll(templateTokens)) {
			templateTokens.removeAll(modelKeys);
			throw new IllegalStateException(
					"Not all template variables were replaced. Missing variable names are " + templateTokens);
		}
	}

}
