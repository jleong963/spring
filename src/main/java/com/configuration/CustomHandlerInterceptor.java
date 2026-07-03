package com.configuration;
import com.utilities.LogUtil;

import java.util.UUID;

import org.jboss.logging.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.exception.RateLimitExceededException;
import com.pojo.bucket4j.CustomBucket;
import com.service.RateLimitService;
import com.validation.RateLimit;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class CustomHandlerInterceptor implements HandlerInterceptor {

	private static final String REQUEST_ID_HEADER = "X-Request-ID";

	private final RateLimitService rateLimitService;

	public CustomHandlerInterceptor(RateLimitService rateLimitService) {
		this.rateLimitService = rateLimitService;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
		String requestId = request.getHeader(REQUEST_ID_HEADER);
		MDC.put(REQUEST_ID_HEADER, requestId != null && !requestId.isBlank() ? requestId : UUID.randomUUID().toString());
		log.info("-Handler interceptor start-");
		try {
			if (request.getDispatcherType() != DispatcherType.REQUEST
					|| request.getAttribute("CustomHandlerInterceptor") != null) {
				return true; // Avoid same full logic run twice for handler interceptor
			}
			request.setAttribute("CustomHandlerInterceptor", Boolean.TRUE);
			// Check if the handler is a HandlerMethod (i.e., a controller method).
			// This allows access to method-level annotations such as @RateLimitHeader.
			if (!(handler instanceof HandlerMethod)) {
				return true;
			}
			// Cast the generic handler object to HandlerMethod to access controller method
			// metadata
			HandlerMethod handlerMethod = (HandlerMethod) handler;
			// Retrieve the @RateLimit annotation (custom) from the handler method, if
			// present
			RateLimit rateLimit = handlerMethod.getMethodAnnotation(RateLimit.class);
			if (rateLimit == null) {
				// Method isn't annotated with @RateLimit, so there's nothing to rate limit
				return true;
			}

			String resolvedIpKey = rateLimitService.resolveKeyFromRequest(log, request, "", "ip");
			String headerName = rateLimit.headerName();
			String resolvedHeaderKey = headerName != null && !headerName.isBlank()
					? rateLimitService.resolveKeyFromRequest(log, request, "header", headerName)
					: null;
			String pathVariable = rateLimit.pathVariable();
			String resolvedPathVariableKey = pathVariable != null && !pathVariable.isBlank()
					? rateLimitService.resolveKeyFromRequest(log, request, "pathVariable", pathVariable)
					: null;
			String requestBodyField = rateLimit.requestBodyField();
			String resolvedRequestBodyFieldKey = requestBodyField != null && !requestBodyField.isBlank()
					? rateLimitService.resolveKeyFromRequest(log, request, "requestBody", requestBodyField)
					: null;

			// Resolve the actual key from the request
			String resolvedKey = resolvedIpKey;
			if (resolvedHeaderKey != null && !resolvedHeaderKey.isBlank()) {
				resolvedKey = resolvedKey != null && !resolvedKey.isBlank() ? resolvedKey.concat("|").concat(resolvedHeaderKey)
						: resolvedHeaderKey;
			}
			if (resolvedPathVariableKey != null && !resolvedPathVariableKey.isBlank()) {
				resolvedKey = resolvedKey != null && !resolvedKey.isBlank()
						? resolvedKey.concat("|").concat(resolvedPathVariableKey)
						: resolvedPathVariableKey;
			}
			if (resolvedRequestBodyFieldKey != null && !resolvedRequestBodyFieldKey.isBlank()) {
				resolvedKey = resolvedKey != null && !resolvedKey.isBlank()
						? resolvedKey.concat("|").concat(resolvedRequestBodyFieldKey)
						: resolvedRequestBodyFieldKey;
			}
			// Try to consume a token
			log.info("Resolved key {}", resolvedKey);
			CustomBucket bucket = rateLimitService.resolveBucket(resolvedKey, request.getRequestURI());
			log.info("Available tokens: {} for key: {} at endpoint {}", bucket.getAvailableTokens(), resolvedKey,
					request.getRequestURI());
			boolean allowed = rateLimitService.tryConsume(bucket);
			log.info("Remaining tokens: {} for key: {} at endpoint {}", bucket.getAvailableTokens(), resolvedKey,
					request.getRequestURI());

			if (!allowed) {
				throw new RateLimitExceededException("Rate limit exceeded");
			}

			return true;
		} catch (Exception e) {
			LogUtil.logError(log, e);
			throw e;
		} catch (Throwable e) {
			LogUtil.logError(log, e);
			throw new Exception(e);
		} finally {
			log.info("-Handler interceptor end-");
			MDC.clear();
		}
	}
}
