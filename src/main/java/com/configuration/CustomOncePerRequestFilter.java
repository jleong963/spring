package com.configuration;
import com.utilities.LogUtil;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.jboss.logging.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.service.AuthService;
import com.utilities.RequestLoggingUtil;

import org.jspecify.annotations.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/*
 * This class will call in class, SecurityConfig to perform authentication & authorization on JWT token receive
 * */
@Slf4j
@Component
public class CustomOncePerRequestFilter extends OncePerRequestFilter {

	private final AuthService authService;

	public CustomOncePerRequestFilter(AuthService authService) {
		this.authService = authService;
	}

	@Override
	protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
			@NonNull FilterChain chain) throws IOException, ServletException {
		try {
			UUID xRequestId = UUID.randomUUID();

			// Wrap request ONCE to cache body
			CachedBodyHttpServletRequest wrappedRequest = new CachedBodyHttpServletRequest(request) {
				@Override
				public String getHeader(String name) {
					if ("X-Request-ID".equals(name)) {
						return xRequestId.toString();
					}
					return super.getHeader(name);
				}

				@Override
				public Enumeration<String> getHeaderNames() {
					Set<String> headerNames = new HashSet<>();
					Enumeration<String> originalHeaders = super.getHeaderNames();
					while (originalHeaders.hasMoreElements()) {
						headerNames.add(originalHeaders.nextElement());
					}
					headerNames.add("X-Request-ID");
					return Collections.enumeration(headerNames);
				}
			};

			// Also add to response for client tracking
			response.setHeader("X-Request-ID", xRequestId.toString());

			MDC.put("X-Request-ID",
					response.getHeader("X-Request-ID") != null && !response.getHeader("X-Request-ID").isBlank()
							? response.getHeader("X-Request-ID")
							: UUID.randomUUID());
			log.info("-Custom once per request filter start-");
			RequestLoggingUtil.logRequestDetails(wrappedRequest, log);

			String signature = wrappedRequest.getHeader("SIGNATURE");
			if (signature != null && !signature.isBlank()) {
				// Validate signature format and length before processing
				if (signature.length() > 1024) {
					log.warn("Signature length exceeds maximum allowed (1024 characters)");
					throw new ServletException("Invalid signature length");
				}
				if (!signature.matches("^[A-Za-z0-9+/=]+$")) {
					log.warn("Signature contains invalid characters");
					throw new ServletException("Invalid signature format");
				}

				// Extract X-SIGNING-KEY-ID header to determine which RSA public key to use
				String signingKeyId = wrappedRequest.getHeader("X-SIGNING-KEY-ID");
				if (signingKeyId == null || signingKeyId.isBlank()) {
					log.warn("X-SIGNING-KEY-ID header is missing or empty");
					throw new ServletException("X-SIGNING-KEY-ID header is required");
				}
				// Validate key ID format to prevent path traversal attacks
				if (!signingKeyId.matches("^[a-zA-Z0-9_-]+$")) {
					log.warn("X-SIGNING-KEY-ID contains invalid characters: {}", signingKeyId);
					throw new ServletException("Invalid X-SIGNING-KEY-ID format");
				}

				// Extract X-TIMESTAMP header used to bind the signature in time (anti-replay)
				String timestamp = wrappedRequest.getHeader("X-TIMESTAMP");
				if (timestamp == null || timestamp.isBlank()) {
					log.warn("X-TIMESTAMP header is missing or empty");
					throw new ServletException("X-TIMESTAMP header is required");
				}
				if (!timestamp.matches("^[0-9]+$")) {
					log.warn("X-TIMESTAMP contains invalid characters");
					throw new ServletException("Invalid X-TIMESTAMP format");
				}

				String requestBody = wrappedRequest.getBody(); // Simply get cached body
				Authentication authentication = authService.isSignatureValid(log, wrappedRequest.getMethod(),
						wrappedRequest.getRequestURI(), timestamp, requestBody, signature, signingKeyId);
				if (authentication.isAuthenticated()) {
					SecurityContextHolder.getContext().setAuthentication(authentication);
					log.info("Authentication successful for URI: {} with key ID: {}", wrappedRequest.getRequestURI(),
							signingKeyId);
				} else {
					log.warn("Authentication failed for URI: {} with key ID: {}", wrappedRequest.getRequestURI(), signingKeyId);
				}
			}

			chain.doFilter(wrappedRequest, response); // Pass the wrapped request down the chain
		} catch (Throwable e) {
			LogUtil.logError(log, e);
			throw e;
		} finally {
			log.info("-Custom once per request filter end-");
			MDC.clear();
		}
	}
}
