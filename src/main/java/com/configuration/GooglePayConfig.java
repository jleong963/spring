package com.configuration;
import com.utilities.LogUtil;

import java.time.Instant;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.crypto.tink.apps.paymentmethodtoken.GooglePaymentsPublicKeysManager;
import com.pojo.Property;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Configuration class for Google Pay integration.
 * Handles initialization of Google Pay public keys and provides health
 * monitoring.
 */
@Configuration
@ConfigurationProperties(prefix = "google.pay")
@Slf4j
@Data // Shortcut for @ToString, @EqualsAndHashCode, @Getter on all fields, and
			// @Setter on all non-final fields, and @RequiredArgsConstructor
@AllArgsConstructor // Generates a constructor with parameters for all fields (regardless of type or
										// annotations)
@NoArgsConstructor // Generates a constructor with no parameters
@Builder(toBuilder = true)
public class GooglePayConfig {

	@Builder.Default
	private boolean init = false;

	private Key key;

	@Autowired(required = false)
	private Property property;

	public GooglePayConfig(Property property, Key key) {
		this.property = property;
		this.key = key;
	}

	/**
	 * Initializes Google Pay by refreshing public keys based on the active profile.
	 * Runs on application startup as a CommandLineRunner.
	 * 
	 * @return CommandLineRunner that performs the initialization
	 */
	@Bean
	CommandLineRunner initGooglePay() {
		return args -> {
			try {
				if (property.getSpring_profiles_active().equalsIgnoreCase("prod")) {
					GooglePaymentsPublicKeysManager.INSTANCE_PRODUCTION.refreshInBackground();
				} else {
					GooglePaymentsPublicKeysManager.INSTANCE_TEST.refreshInBackground();
				}
				init = true;
			} catch (Exception e) {
				init = false;
				LogUtil.logError(log, e);
				throw e;
			}
		};
	}

	/**
	 * Provides a health indicator for Google Pay initialization status.
	 * Used by Spring Boot Actuator to monitor the health of Google Pay integration.
	 * 
	 * @return HealthIndicator that reports UP if Google Pay is initialized, DOWN
	 *         otherwise
	 */
	@Bean
	HealthIndicator googlePaymentHealthIndicator() {
		return () -> {
			if (init) {
				return Health.up().build();
			}
			return Health.down()
					.withDetail("message", "Google Pay not initialized")
					.withDetail("timestamp", Instant.now())
					.build();
		};
	}

	/**
	 * Marks Google Pay as uninitialized on application shutdown or restart. The Tink
	 * {@link GooglePaymentsPublicKeysManager} singletons expose no closeable handle
	 * (their background refresh runs on daemon threads that the JVM reclaims on
	 * exit), so shutdown simply flips the readiness flag and logs, ensuring the
	 * health indicator reports DOWN while the context is stopping.
	 */
	@PreDestroy
	void shutdownGooglePay() {
		try {
			log.info("Shutting down Google Pay...");
			init = false;
			log.info("Google Pay shut down");
		} catch (Exception e) {
			LogUtil.logError(log, e);
		}
	}

	@Data // Shortcut for @ToString, @EqualsAndHashCode, @Getter on all fields, and
				// @Setter on all non-final fields, and @RequiredArgsConstructor
	@AllArgsConstructor // Generates a constructor with parameters for all fields (regardless of type or
											// annotations)
	@NoArgsConstructor // Generates a constructor with no parameters
	@Builder(toBuilder = true)
	public static class Key {

		@JsonProperty(value = "path")
		private String path;
	}
}
