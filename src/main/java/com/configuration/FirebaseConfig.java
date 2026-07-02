package com.configuration;
import com.utilities.LogUtil;

import java.io.InputStream;
import java.time.Instant;

import jakarta.annotation.PreDestroy;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Configuration
@ConfigurationProperties(prefix = "google.fcm")
@Slf4j
@Data // Shortcut for @ToString, @EqualsAndHashCode, @Getter on all fields, and
			// @Setter on all non-final fields, and @RequiredArgsConstructor
@AllArgsConstructor // Generates a constructor with parameters for all fields (regardless of type or
										// annotations)
@NoArgsConstructor // Generates a constructor with no parameters
@Builder(toBuilder = true)
public class FirebaseConfig {

	@Builder.Default
	private boolean init = false;

	private Credentials credentials;

	@Bean
	CommandLineRunner initFirebase() {
		return args -> {
			try {
				if (!FirebaseApp.getApps().isEmpty()) {
					log.info("Firebase already initialized");
					init = true;
					return;
				}

				InputStream inputStream = getClass().getClassLoader().getResourceAsStream(credentials.getPath());
				if (inputStream != null) {
					GoogleCredentials googleCredentials = GoogleCredentials.fromStream(inputStream);
					log.info("Initializing Firebase with classpath credentials: {}", credentials.getPath());
					FirebaseOptions firebaseOptions = FirebaseOptions.builder()
							.setCredentials(googleCredentials)
							.build();
					FirebaseApp.initializeApp(firebaseOptions);
					log.info("Firebase initialized");
					init = true;
				} else {
					log.info("Couldn't find {} on classpath.", credentials.getPath());
				}
			} catch (Exception e) {
				init = false;
				LogUtil.logError(log, e);
				throw e;
			}
		};
	}

	@Bean
	HealthIndicator firebaseHealthIndicator() {
		return () -> {
			if (init) {
				return Health.up().build();
			}
			return Health.down()
					.withDetail("message", "Firebase not initialized.")
					.withDetail("timestamp", Instant.now())
					.build();
		};
	}

	/**
	 * Releases Firebase resources on application shutdown or restart. Deletes any
	 * initialized {@link FirebaseApp} so its background threads and connections are
	 * cleanly torn down, allowing the context to stop gracefully.
	 */
	@PreDestroy
	void shutdownFirebase() {
		try {
			if (FirebaseApp.getApps().isEmpty()) {
				return;
			}
			log.info("Shutting down Firebase...");
			FirebaseApp.getInstance().delete();
			init = false;
			log.info("Firebase shut down");
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
	public static class Credentials {

		private String path;
	}
}
