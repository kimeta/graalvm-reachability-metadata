/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org_hibernate_orm.hibernate_jcache;

import org.assertj.core.api.Assertions;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.persistence.Cacheable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

class Hibernate_jcacheTest {

    private StandardServiceRegistry registry;
    private SessionFactory sessionFactory;

    @BeforeEach
    void setUp() {
        registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.hbm2ddl.auto", "create-drop")
                .applySetting("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
                .applySetting("hibernate.connection.driver_class", "org.h2.Driver")
                .applySetting("hibernate.connection.url", "jdbc:h2:mem:hjcache;DB_CLOSE_DELAY=-1;MODE=LEGACY")
                .applySetting("hibernate.connection.username", "sa")
                .applySetting("hibernate.connection.password", "")
                // JCache / caching
                .applySetting("hibernate.cache.use_second_level_cache", "true")
                .applySetting("hibernate.cache.use_query_cache", "true")
                .applySetting("hibernate.cache.region.factory_class", "org.hibernate.cache.jcache.JCacheRegionFactory")
                // Auto-create missing JCache regions (entity, natural-id, query)
                .applySetting("hibernate.cache.jcache.missing_cache_strategy", "create")
                // Make the provider explicit to avoid provider discovery ambiguity
                .applySetting("hibernate.javax.cache.provider", "org.ehcache.jsr107.EhcacheCachingProvider")
                // Stats for assertions
                .applySetting("hibernate.generate_statistics", "true")
                .build();

        sessionFactory = new MetadataSources(registry)
                .addAnnotatedClass(Item.class)
                .buildMetadata()
                .buildSessionFactory();
    }

    @AfterEach
    void tearDown() {
        if (sessionFactory != null) {
            sessionFactory.close();
        }
        if (registry != null) {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    @Test
    void secondLevelCache_entityPutAndHitAcrossSessions() {
        Statistics stats = sessionFactory.getStatistics();
        stats.clear();

        Long id;
        // Persist an entity
        try (Session s = sessionFactory.openSession()) {
            Transaction tx = s.beginTransaction();
            Item item = new Item();
            item.setName("cached-item");
            s.persist(item);
            tx.commit();
            id = item.getId();
        }

        // First load: should hit DB and populate L2 cache (put)
        try (Session s = sessionFactory.openSession()) {
            Item loaded = s.find(Item.class, id);
            Assertions.assertThat(loaded).isNotNull();
            Assertions.assertThat(loaded.getName()).isEqualTo("cached-item");
        }

        long putsAfterFirstLoad = stats.getSecondLevelCachePutCount();
        long hitsBeforeSecondLoad = stats.getSecondLevelCacheHitCount();

        // Second load in a new Session: should be served from L2 (hit)
        try (Session s = sessionFactory.openSession()) {
            Item loaded = s.find(Item.class, id);
            Assertions.assertThat(loaded).isNotNull();
            Assertions.assertThat(loaded.getName()).isEqualTo("cached-item");
        }

        long hitsAfterSecondLoad = stats.getSecondLevelCacheHitCount();

        Assertions.assertThat(putsAfterFirstLoad)
                .as("Entity should have been stored in L2 cache on first load/commit")
                .isGreaterThanOrEqualTo(1L);
        Assertions.assertThat(hitsAfterSecondLoad - hitsBeforeSecondLoad)
                .as("Second load should hit the second-level cache")
                .isGreaterThanOrEqualTo(1L);
    }

    @Test
    void queryCache_hitsOnRepeatedCacheableQuery() {
        Statistics stats = sessionFactory.getStatistics();
        stats.clear();

        // Seed data
        try (Session s = sessionFactory.openSession()) {
            Transaction tx = s.beginTransaction();
            for (int i = 0; i < 5; i++) {
                Item item = new Item();
                item.setName("alpha-" + i);
                s.persist(item);
            }
            for (int i = 0; i < 3; i++) {
                Item item = new Item();
                item.setName("beta-" + i);
                s.persist(item);
            }
            tx.commit();
        }

        // First execution: should hit DB and populate query cache
        try (Session s = sessionFactory.openSession()) {
            s.createQuery("select i from Item i where i.name like :p", Item.class)
                    .setParameter("p", "alpha-%")
                    .setCacheable(true)
                    .list();
        }

        long queryPuts = stats.getQueryCachePutCount();
        long queryHitsBefore = stats.getQueryCacheHitCount();

        // Second execution with same query + params: should be served from query cache
        try (Session s = sessionFactory.openSession()) {
            var results = s.createQuery("select i from Item i where i.name like :p", Item.class)
                    .setParameter("p", "alpha-%")
                    .setCacheable(true)
                    .list();

            Assertions.assertThat(results)
                    .extracting(Item::getName)
                    .allMatch(n -> n.startsWith("alpha-"));
            Assertions.assertThat(results).hasSize(5);
        }

        long queryHitsAfter = stats.getQueryCacheHitCount();

        Assertions.assertThat(queryPuts)
                .as("First execution should populate the query cache")
                .isGreaterThanOrEqualTo(1L);
        Assertions.assertThat(queryHitsAfter - queryHitsBefore)
                .as("Second execution should hit the query cache")
                .isGreaterThanOrEqualTo(1L);
    }

    @Test
    void evictEntityData_causesMissOnNextLoad() {
        Statistics stats = sessionFactory.getStatistics();
        stats.clear();

        Long id;
        try (Session s = sessionFactory.openSession()) {
            Transaction tx = s.beginTransaction();
            Item item = new Item();
            item.setName("evict-me");
            s.persist(item);
            tx.commit();
            id = item.getId();
        }

        // Warm up L2 cache (first load -> L2 put)
        try (Session s = sessionFactory.openSession()) {
            Item firstLoad = s.find(Item.class, id);
            Assertions.assertThat(firstLoad).isNotNull();
        }

        // Trigger an L2 cache hit (second load in a new Session)
        try (Session s = sessionFactory.openSession()) {
            Item secondLoad = s.find(Item.class, id);
            Assertions.assertThat(secondLoad).isNotNull();
        }

        long hitsBeforeEvict = stats.getSecondLevelCacheHitCount();

        // Evict all Item entities from L2 cache
        sessionFactory.getCache().evictEntityData(Item.class);

        long missesBeforeReload = stats.getSecondLevelCacheMissCount();

        // Next load should miss L2 and repopulate
        try (Session s = sessionFactory.openSession()) {
            Item reloaded = s.find(Item.class, id);
            Assertions.assertThat(reloaded).isNotNull();
            Assertions.assertThat(reloaded.getName()).isEqualTo("evict-me");
        }

        long missesAfterReload = stats.getSecondLevelCacheMissCount();

        Assertions.assertThat(hitsBeforeEvict)
                .as("There should have been at least one L2 hit prior to eviction")
                .isGreaterThanOrEqualTo(1L);
        Assertions.assertThat(missesAfterReload - missesBeforeReload)
                .as("After eviction, next load should miss L2 cache")
                .isGreaterThanOrEqualTo(1L);
    }

    @Entity(name = "Item")
    @Table(name = "items")
    @Cacheable
    @Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
    public static class Item {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(nullable = false)
        private String name;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
