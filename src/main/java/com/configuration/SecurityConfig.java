package com.configuration;

import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.ExceptionHandlingConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

import com.enums.ResponseCode;
import tools.jackson.databind.ObjectMapper;
import com.pojo.ApiResponse;
import com.pojo.Property;
import com.utilities.Tool;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.slf4j.Slf4j;

/*
 * Here is to configure spring security
 * */
@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig implements WebMvcConfigurer {

	@Value("${server.ssl.enabled}")
	private boolean sslEnabled;

	private final CustomOncePerRequestFilter customOncePerRequestFilter;

	private final Property property;

	private final CustomHandlerInterceptor customHandlerInterceptor;

	private final Tool tool;

	private final ObjectMapper objectMapper;

	public SecurityConfig(CustomOncePerRequestFilter customOncePerRequestFilter, Property property,
			CustomHandlerInterceptor customHandlerInterceptor, Tool tool, ObjectMapper objectMapper) {
		this.customOncePerRequestFilter = customOncePerRequestFilter;
		this.property = property;
		this.customHandlerInterceptor = customHandlerInterceptor;
		this.tool = tool;
		this.objectMapper = objectMapper;
	}

	@Bean
	@Order(1)
	SecurityFilterChain httpSecurityFilterChain(HttpSecurity http) throws Exception {
		// HTTP listener (port 8080)
		return applyCommonConfig(http.securityMatcher(new CustomRequestMatcher(8080)))
				.authorizeHttpRequests((requests) -> requests
						// Authorize only the initial REQUEST dispatch; internal async/forward/error
						// re-dispatches are already authorized and would otherwise lose the
						// SecurityContext (stateless + OncePerRequestFilter skips async).
						.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.FORWARD, DispatcherType.ERROR).permitAll()
						.requestMatchers(HttpMethod.POST, "/v1/template/post").permitAll()
						.requestMatchers(HttpMethod.POST, "/v1/template/get").permitAll()
						.requestMatchers(HttpMethod.POST, "/v1/template/get-async").permitAll()
						.requestMatchers(HttpMethod.POST, "/v1/template/put").permitAll()
						.requestMatchers(HttpMethod.POST, "/v1/template/delete").permitAll()
						// .requestMatchers(HttpMethod.POST, "<endpoint - example,
						// /v1/test>").hasAnyAuthority("SCOPE_<user type>_<action>_<permission:
						// read/write>")
						.anyRequest().authenticated() // All other endpoints must authenticated
				).build();// Return the built filter chain
	}

	@Bean
	@Order(2)
	SecurityFilterChain httpsSecurityFilterChain(HttpSecurity http) throws Exception {
		// HTTPS listener (port 8443)
		return applyCommonConfig(http.securityMatcher(new CustomRequestMatcher(8443)))
				.authorizeHttpRequests((requests) -> requests
						// Authorize only the initial REQUEST dispatch; internal async/forward/error
						// re-dispatches are already authorized and would otherwise lose the
						// SecurityContext (stateless + OncePerRequestFilter skips async).
						.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.FORWARD, DispatcherType.ERROR).permitAll()
						.requestMatchers(HttpMethod.POST, "/v1/template/post").permitAll()
						.requestMatchers(HttpMethod.POST, "/v1/template/get").permitAll()
						.requestMatchers(HttpMethod.POST, "/v1/template/get-async").permitAll()
						.requestMatchers(HttpMethod.POST, "/v1/template/put").permitAll()
						.requestMatchers(HttpMethod.POST, "/v1/template/delete").permitAll()
						// .requestMatchers(HttpMethod.POST, "<endpoint - example,
						// /v1/test>").hasAnyAuthority("SCOPE_<user type>_<action>_<permission:
						// read/write>")
						.anyRequest().authenticated() // All other endpoints must authenticated
				).build();// Return the built filter chain
	}

	@Bean
	@Order(3)
	SecurityFilterChain managementSecurityFilterChain(HttpSecurity http) throws Exception {
		// Management/actuator listener (port 8444)
		return applyCommonConfig(http.securityMatcher(new CustomRequestMatcher(8444)))
				.authorizeHttpRequests((requests) -> requests.requestMatchers("/actuator/**").permitAll()
						// .requestMatchers(HttpMethod.POST, "<endpoint - example,
						// /v1/test>").hasAnyAuthority("SCOPE_<user type>_<action>_<permission:
						// read/write>")
						.anyRequest().denyAll() // All other endpoints will be deny.
				).build();// Return the built filter chain
	}

	/**
	 * Applies the security configuration shared by every filter chain (headers,
	 * CSRF, CORS, stateless sessions, exception handling and the custom
	 * authentication filter). Each chain then appends its own authorization rules.
	 */
	private HttpSecurity applyCommonConfig(HttpSecurity http) throws Exception {
		return http.headers(this::applySecurityHeaders)
				// Disable CSRF protection because app is stateless (e.g., uses JWT tokens)
				.csrf(csrf -> csrf.disable())
				// Enable Cross-Origin Resource Sharing with custom config
				.cors(cors -> cors.configurationSource(corsConfigurationSource()))
				// Stateless session management: no HTTP session stored server-side
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				// Handle authentication & access errors with custom exceptions
				.exceptionHandling(this::applyExceptionHandling)
				// Register custom filter before Spring Security's BasicAuthenticationFilter
				.addFilterBefore(customOncePerRequestFilter, BasicAuthenticationFilter.class);
	}

	/**
	 * Security response headers applied to every filter chain.
	 */
	private void applySecurityHeaders(HeadersConfigurer<HttpSecurity> headers) {
		// Force HTTPS communication and prevent protocol downgrade attacks.
		if (sslEnabled) {
			headers.httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true)// Applies HSTS to subdomains
					.maxAgeInSeconds(31536000)// Cache duration: 1 year
			);
		}
		// Mitigate XSS, clickjacking, and content injection attacks
		headers.contentSecurityPolicy(csp -> csp.policyDirectives(
				"default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; font-src 'self'; connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'; upgrade-insecure-requests;"));
		// Prevent the site from being embedded in frames (defense against clickjacking)
		headers.frameOptions(frame -> frame.deny());
		// X-Content-Type-Options: nosniff is enabled by default (do NOT disable it) to
		// prevent MIME-sniffing attacks.
		// Referrer policy to prevent leaking full URLs when navigating offsite
		headers.referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN));
		// Disable the legacy X-XSS-Protection header. Modern
		// browsers rely on the Content-Security-Policy above for XSS defense; the
		// legacy header is deprecated and can introduce vulnerabilities when enabled.
		headers.xssProtection(xss -> xss.disable());
		// Permissions policy to restrict browser features
		headers.addHeaderWriter(new StaticHeadersWriter("Permissions-Policy",
				"geolocation=(), microphone=(), camera=(), payment=(), usb=(), magnetometer=(), gyroscope=(), accelerometer=()"));
	}

	/**
	 * Custom authentication / authorization error responses shared by every filter
	 * chain.
	 */
	private void applyExceptionHandling(ExceptionHandlingConfigurer<HttpSecurity> exception) {
		exception.authenticationEntryPoint((request, response, authEx) ->
		// Fired when an unauthenticated request tries to access a protected resource
		writeErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthenticated access."))
				.accessDeniedHandler((request, response, accessDeniedEx) ->
				// Fired when an authenticated user lacks required permissions
				writeErrorResponse(response, HttpServletResponse.SC_FORBIDDEN,
						"Access denied - Insufficient permissions."));
	}

	/**
	 * Writes a JSON {@link ApiResponse} error body with the given HTTP status.
	 */
	private void writeErrorResponse(HttpServletResponse response, int status, String message) throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_JSON);
		response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.builder()
				.resp_code(ResponseCode.UNAUTHORIZED_ACCESS.getResponse_code())
				.resp_msg(message)
				.datetime(tool.getTodayDateTimeInString())
				.build()));
	}

	/**
	 * Defines global CORS configuration. - Restricts origins to those specified in
	 * application properties. - Allows common HTTP methods and all headers. -
	 * Enables credentials support (e.g., for cookies).
	 */
	@Bean
	CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(property.getAllowed_origins()); // Whitelist only trusted domains
		configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));// Common HTTP methods
		configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-ID", "SIGNATURE",
				"X-SIGNING-KEY-ID", "X-TIMESTAMP", "Accept"));// Explicitly define allowed headers
		configuration.setAllowCredentials(true);// Required for session cookies
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);// Apply to all endpoints
		return source;
	}

	/**
	 * Adds a custom rate limiting interceptor to all endpoints. This enforces
	 * per-path rate limit rules defined elsewhere.
	 */
	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(customHandlerInterceptor).addPathPatterns("/**"); // Customize your target paths
	}
}
