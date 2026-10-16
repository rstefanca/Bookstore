package cz.codingmonkey.bookstore.shared;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.transaction.TransactionAwareCacheManagerProxy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * In-memory caches (Caffeine), created on first use by name. Puts and evictions inside a transaction take effect
 * only after it commits, so a rolled-back change never evicts and a reader can't re-cache data that is about to be
 * replaced by the committing change. Each application instance has its own caches; running several instances
 * would need a shared cache or cross-instance invalidation.
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
class CachingConfiguration {

    @Bean
    CacheManager cacheManager(@Value("${bookstore.cache.caffeine-spec}") String caffeineSpec) {
        var caffeine = new CaffeineCacheManager();
        caffeine.setCacheSpecification(caffeineSpec);
        return new TransactionAwareCacheManagerProxy(caffeine);
    }
}
