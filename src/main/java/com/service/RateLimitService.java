package com.service;
import com.utilities.LogUtil;

import com.configuration.RateLimitProperties;
import com.pojo.bucket4j.CustomBucket;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.github.bucket4j.Bandwidth;
import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.text.StringEscapeUtils;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.HandlerMapping;

import javax.cache.Cache;
import javax.cache.CacheManager;

import java.io.BufferedReader;
import java.util.Map;

@Service
public class RateLimitService {

	private final Cache<String, CustomBucket> cache;

	private final RateLimitProperties rateLimitProperties;

	private final ObjectMapper objectMapper;

	/**
	 * Constructor initializes the rate limit cache using the provided CacheManager.
	 *
	 * @param cacheManager Spring's CacheManager used to retrieve a named cache.
	 * @param objectMapper Jackson mapper used to read fields from JSON request bodies.
	 */
	public RateLimitService(RateLimitProperties rateLimitProperties, CacheManager cacheManager, ObjectMapper objectMapper) {
		this.rateLimitProperties = rateLimitProperties;
		this.cache = cacheManager.getCache("buckets", String.class, CustomBucket.class);
		this.objectMapper = objectMapper;
	}

	/**
	 * Resolves a rate-limiting bucket for a given client key and API path.
	 * If the bucket doesn't exist in the cache, it creates and caches a new one
	 * with limits defined by the path configuration.
	 *
	 * @param key  A unique identifier (e.g., IP address or API key) for the caller.
	 * @param path The API endpoint being accessed, used to determine the limit.
	 * @return The Bucket associated with the given key.
	 */
	public CustomBucket resolveBucket(String key, String path) {
		CustomBucket bucket = cache.get(key);// Try to fetch the rate limit bucket from the cache
		if (bucket == null) {
			Bandwidth bandwidth = rateLimitProperties.getLimitForPath(path);
			// Build a new token bucket with the defined bandwidth limit
			bucket = new CustomBucket(bandwidth);
			// Store the newly created bucket in the cache for future use
			cache.put(key, bucket);
		}
		return bucket;
	}

	/**
	 * Consumes a single token. When {@code rate.limit.max-wait-millis} is greater
	 * than 0 the request waits (auto-retry) for the bucket to refill up to that
	 * window before being rejected, instead of being dropped immediately on a
	 * momentary burst. Returns {@code false} if no token becomes available in time.
	 */
	public boolean tryConsume(CustomBucket bucket) {
		long maxWaitMillis = rateLimitProperties.getMaxWaitMillis();
		if (maxWaitMillis <= 0) {
			return bucket.tryConsume(1);
		}
		try {
			return bucket.tryConsume(1, java.time.Duration.ofMillis(maxWaitMillis));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	public String resolveKeyFromRequest(Logger log, HttpServletRequest request, String keyType, String keyValues)
			throws Throwable {
		String result = "";
		try {
			for (String keyValue : keyValues.split(",")) {
				String resolvedKey = "";
				switch (keyType) {
					case "header":
						resolvedKey = request.getHeader(keyValue);
						break;
					case "pathVariable":
						resolvedKey = resolvePathVariable(request, keyValue);
						break;
					case "requestBody":
						resolvedKey = resolveRequestBodyField(log, request, keyValue);
						break;
					default:
						resolvedKey = getClientIP(request);
						break;
				}
				if (resolvedKey != null && !resolvedKey.isBlank()) {
					result += (result.length() > 0 ? "," : "") + keyValue + ":" + resolvedKey;
				}
			}
		} catch (Throwable e) {
			LogUtil.logError(log, e);
			throw e;
		}
		// Prepend the keyType to ensure uniqueness across different key types
		return result;
	}

	private String getClientIP(HttpServletRequest request) {
		String xForwardedFor = request.getHeader("X-Forwarded-For");
		if (xForwardedFor != null && !xForwardedFor.isBlank()) {
			// Get the first IP which is the client's IP
			return xForwardedFor.split(",")[0].trim();
		}
		return request.getRemoteAddr();
	}

	/**
	 * Utility method to extract path variable from the current request.
	 *
	 * @param request      the incoming HTTP request
	 * @param variableName the name of the path variable to resolve (e.g. "userId"
	 *                     in /users/{userId})
	 * @return the value of the path variable if found, otherwise null
	 */
	private String resolvePathVariable(HttpServletRequest request, String variableName) {
		@SuppressWarnings("unchecked")
		Map<String, String> pathVariables = (Map<String, String>) request
				.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);

		if (pathVariables != null && pathVariables.containsKey(variableName)) {
			return pathVariables.get(variableName);
		}
		return null;
	}

	/**
	 * Resolves a single named field used for rate-limit keying.
	 *
	 * Looks up the field first as a form/query parameter, then falls back to the
	 * JSON request body. The body is cached by CachedBodyHttpServletRequest (added
	 * in CustomOncePerRequestFilter), so re-reading it here does not consume the
	 * stream the controller later reads. Dot-notated field paths (e.g. "user.id")
	 * are supported for nested JSON fields.
	 *
	 * @param request   the incoming HttpServletRequest (possibly wrapped)
	 * @param fieldPath the path to the field in dot notation (e.g. "user.email")
	 * @return the value of the field as a String, or null if not found or
	 *         unreadable
	 */
	private String resolveRequestBodyField(Logger log, HttpServletRequest request, String fieldPath) throws Throwable {
		try {
			if (fieldPath == null || fieldPath.isBlank()) {
				return null;
			}
			fieldPath = fieldPath.trim();

			// 1) Try form/query parameter first (no body parsing required).
			String parameterValue = request.getParameter(fieldPath);
			if (parameterValue != null && !parameterValue.isBlank()) {
				return StringEscapeUtils.escapeHtml4(parameterValue);
			}

			// 2) Fall back to the JSON body. The request body is cached by
			// CachedBodyHttpServletRequest in CustomOncePerRequestFilter, so it can be
			// re-read here without consuming the stream the controller will read later.
			String contentType = request.getContentType();
			if (contentType != null && contentType.toLowerCase().contains("json")) {
				String body = readBody(request);
				if (body != null && !body.isBlank()) {
					JsonNode root = objectMapper.readTree(body);
					// Support nested fields via dot-path (e.g. "user.id") using a JSON Pointer.
					JsonNode node = root.at("/" + fieldPath.replace('.', '/'));
					if (node != null && !node.isMissingNode() && !node.isNull()) {
						String value = node.isValueNode() ? node.asText() : node.toString();
						if (value != null && !value.isBlank()) {
							return StringEscapeUtils.escapeHtml4(value);
						}
					}
				}
			}

			return null;
		} catch (Throwable e) {
			LogUtil.logError(log, e);
			throw e;
		}
	}

	private String readBody(HttpServletRequest request) throws Throwable {
		StringBuilder body = new StringBuilder();
		try (BufferedReader reader = request.getReader()) {
			if (reader == null) {
				return null;
			}
			char[] buffer = new char[1024];
			int read;
			while ((read = reader.read(buffer)) != -1) {
				body.append(buffer, 0, read);
			}
		}
		return body.toString();
	}
}
