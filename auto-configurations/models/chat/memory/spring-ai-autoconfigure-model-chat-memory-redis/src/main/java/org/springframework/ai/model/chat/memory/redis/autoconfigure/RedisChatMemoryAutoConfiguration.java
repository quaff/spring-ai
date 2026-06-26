/*
 * Copyright 2023-present the original author or authors.
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

package org.springframework.ai.model.chat.memory.redis.autoconfigure;

import org.jspecify.annotations.Nullable;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.RedisClient;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.redis.RedisChatMemoryRepository;
import org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

/**
 * Auto-configuration for Redis-based chat memory implementation.
 *
 * @author Brian Sam-Bodden
 * @author Yanming Zhou
 */
@AutoConfiguration(before = ChatMemoryAutoConfiguration.class)
@ConditionalOnClass({ RedisChatMemoryRepository.class, RedisClient.class, DataRedisProperties.class })
@EnableConfigurationProperties(RedisChatMemoryProperties.class)
public class RedisChatMemoryAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean({ RedisChatMemoryRepository.class, ChatMemory.class, ChatMemoryRepository.class })
	public RedisChatMemoryRepository redisChatMemory(RedisChatMemoryProperties properties,
			ObjectProvider<DataRedisProperties> dataRedisProperties) {
		RedisChatMemoryRepository.Builder builder = RedisChatMemoryRepository.builder()
			.jedisClient(jedisClient(properties, dataRedisProperties.getIfAvailable()));

		// Apply configuration if provided
		if (StringUtils.hasText(properties.getIndexName())) {
			builder.indexName(properties.getIndexName());
		}

		if (StringUtils.hasText(properties.getKeyPrefix())) {
			builder.keyPrefix(properties.getKeyPrefix());
		}

		if (properties.getTimeToLive() != null && properties.getTimeToLive().toSeconds() > 0) {
			builder.timeToLive(properties.getTimeToLive());
		}

		builder.initializeSchema(properties.getInitializeSchema())
			.maxConversationIds(properties.getMaxConversationIds())
			.maxMessagesPerConversation(properties.getMaxMessagesPerConversation());

		if (!properties.getMetadataFields().isEmpty()) {
			builder.metadataFields(properties.getMetadataFields());
		}

		return builder.build();
	}

	private RedisClient jedisClient(RedisChatMemoryProperties properties,
			@Nullable DataRedisProperties dataRedisProperties) {
		String host = properties.getHost();
		if (host == null) {
			if (dataRedisProperties != null) {
				host = dataRedisProperties.getHost();
			}
			else {
				host = "localhost";
			}
		}
		Integer port = properties.getPort();
		if (port == null) {
			if (dataRedisProperties != null) {
				port = dataRedisProperties.getPort();
			}
			else {
				port = 6379;
			}
		}
		var builder = RedisClient.builder().hostAndPort(host, port);
		var clientConfigBuilder = DefaultJedisClientConfig.builder().database(properties.getDatabase());
		if (dataRedisProperties != null) {
			clientConfigBuilder.ssl(dataRedisProperties.getSsl().isEnabled())
				.clientName(dataRedisProperties.getClientName())
				.password(dataRedisProperties.getPassword());
			if (dataRedisProperties.getTimeout() != null) {
				clientConfigBuilder.timeoutMillis((int) dataRedisProperties.getTimeout().toMillis());
			}
			builder.clientConfig(clientConfigBuilder.build());
		}
		return builder.build();
	}

}
