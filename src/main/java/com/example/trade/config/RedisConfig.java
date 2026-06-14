package com.example.trade.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置类 —— 配置 Redis 连接和序列化方式。
 *
 * 序列化策略：
 * - Key：使用 StringRedisSerializer（可读性好）
 * - Value：使用 GenericJackson2JsonRedisSerializer（支持复杂对象）
 */
@Configuration
public class RedisConfig {

    /**
     * 配置 RedisTemplate，设置序列化方式。
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Key 序列化：String
        StringRedisSerializer keySerializer = new StringRedisSerializer();
        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);

        // Value 序列化：JSON
        GenericJackson2JsonRedisSerializer valueSerializer = new GenericJackson2JsonRedisSerializer(redisObjectMapper());
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * 配置 Redis 专用的 ObjectMapper。
     * 支持 Java 8 时间类型、忽略未知属性等。
     */
    private ObjectMapper redisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        // 忽略未知属性（反序列化时不报错）
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        // 支持所有字段（包括 private）
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        // 注册 Java 8 时间模块
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }
}
