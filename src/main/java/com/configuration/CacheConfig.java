package com.configuration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.jcache.JCacheCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.pojo.bucket4j.CustomBucket;

import java.util.concurrent.TimeUnit;

import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.configuration.MutableConfiguration;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.Duration;
import javax.cache.spi.CachingProvider;

@Configuration
public class CacheConfig {

	/**
	 * Provides the default JCache CacheManager.
	 * This is required to create and manage named caches like "buckets".
	 *
	 * @return a JSR-107 compatible CacheManager
	 */
	@Bean(name = "customCacheManager")
	CacheManager cacheManager() {
		// Get the default caching provider (e.g., Ehcache, Hazelcast, etc.)
		CachingProvider provider = Caching.getCachingProvider();
		CacheManager cacheManager = provider.getCacheManager();

		MutableConfiguration<String, CustomBucket> customBucketConfig = new MutableConfiguration<String, CustomBucket>()
				.setTypes(String.class, CustomBucket.class) // enforce proper types
				.setStoreByValue(false)// Store references instead of copying the Bucket object
				.setExpiryPolicyFactory(CreatedExpiryPolicy.factoryOf(new Duration(TimeUnit.SECONDS, 1 * 60)))// Expiration of
																																																			// bucket per
																																																			// minute & will
																																																			// auto create a
																																																			// new one from
																																																			// incoming
																																																			// request
				.setStatisticsEnabled(true);// Enable cache hit/miss statistics
		if (cacheManager.getCache("buckets") == null) {
			// Can use along side with @Cacheable("<cache name>" on method)
			cacheManager.createCache("buckets", customBucketConfig);
		}

		return cacheManager;
	}

	/**
	 * Adapts the JCache (Caffeine) provider above into Spring's cache abstraction.
	 * {@code @EnableCaching} requires an {@link org.springframework.cache.CacheManager};
	 * Spring Boot 4 no longer auto-bridges a user-defined {@link javax.cache.CacheManager}
	 * bean into one, so it is registered explicitly here. The rate limiter continues to use
	 * the JCache manager ({@code customCacheManager}) directly, so both share one provider.
	 *
	 * @param jCacheCacheManager the JSR-107 CacheManager defined above
	 * @return a Spring CacheManager backing {@code @Cacheable} on the same JCache provider
	 */
	@Bean
	org.springframework.cache.CacheManager springCacheManager(@Qualifier("customCacheManager") CacheManager jCacheCacheManager) {
		return new JCacheCacheManager(jCacheCacheManager);
	}

	/**
	 * Binds custom rate-limiting configuration properties.
	 * This bean can be used to read values from application.properties or
	 * application.yml.
	 *
	 * @return a RateLimitProperties bean instance
	 */
	@Bean
	RateLimitProperties rateLimitProperties() {
		return new RateLimitProperties();
	}
}
