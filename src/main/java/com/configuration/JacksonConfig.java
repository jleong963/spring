package com.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class JacksonConfig {

	@Bean
	public JsonMapper objectMapper() {
		return JsonMapper.builder()
				// Ignore unknown properties during deserialization (Jackson 3 default, kept
				// explicit for clarity)
				.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
				// ACCEPT_EMPTY_STRING_AS_NULL_OBJECT:
				// Treats empty strings ("") as null when deserializing into Object types.
				// Example: {"user": ""} will deserialize user field as null instead of empty
				// string
				// Useful when APIs return empty strings instead of null for missing/empty
				// objects
				// Without this: empty string would cause deserialization errors for complex
				// types
				.enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
				// Case-insensitive deserialization
				.enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
				// Case-insensitive deserialization
				.enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
				// FAIL_ON_EMPTY_BEANS:
				// By default, Jackson throws an exception when serializing objects with no
				// properties
				// Disabling this allows serializing "empty" beans (classes with no
				// getters/fields) as {}
				// Example: public class EmptyClass {} will serialize as {} instead of throwing
				// exception
				// Useful when dealing with marker classes, DTOs that may be empty, or dynamic
				// objects
				.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
				// Exclude null & blank values from serialization
				.changeDefaultPropertyInclusion(incl -> JsonInclude.Value.construct(JsonInclude.Include.NON_EMPTY, JsonInclude.Include.NON_EMPTY))
				// Java 8 date/time support is built into Jackson 3 (no JavaTimeModule needed)
				// and dates serialize as ISO-8601 strings by default, not timestamps
				.build();
	}
}
