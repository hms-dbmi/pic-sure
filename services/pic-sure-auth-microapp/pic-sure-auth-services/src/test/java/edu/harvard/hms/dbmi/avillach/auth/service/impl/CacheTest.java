package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import edu.harvard.hms.dbmi.avillach.auth.config.CustomKeyGenerator;
import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.repository.AccessRuleRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.ApplicationRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.ConnectionRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserRepository;
import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ExtendWith(MockitoExtension.class)
@ContextConfiguration(classes = {
        UserService.class,
        AccessRuleService.class,
        CustomKeyGenerator.class,
        CacheEvictionService.class

})
@Import(CacheTest.TestCacheConfig.class)
public class CacheTest {

    @MockBean
    private AccessRuleRepository accessRuleRepository;

    @MockBean
    private BasicMailService basicMailService;

    @MockBean
    private TOSService tosService;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private ConnectionRepository connectionRepository;

    @MockBean
    private ApplicationRepository applicationRepository;

    @MockBean
    private RoleService roleService;

    @MockBean
    private JWTUtil jwtUtil;

    @MockBean
    private SessionService sessionService;

    @Autowired
    private AccessRuleService accessRuleService;

    @Autowired
    private UserService userService;

    @Autowired
    private CacheEvictionService cacheEvictionService;

    @Autowired
    private CacheManager cacheManager;

    @Mock
    private User mockUser;

    @Mock
    private Application mockApplication;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        Objects.requireNonNull(cacheManager.getCache("mergedRulesCache")).clear();
        Objects.requireNonNull(cacheManager.getCache("preProcessedAccessRules")).clear();
        Objects.requireNonNull(cacheManager.getCache("mergedTemplateCache")).clear();
    }

    @Test
    public void testMergedRulesCache() throws JsonProcessingException {
        Set<Privilege> mockPrivileges = new HashSet<>();
        when(mockUser.getSubject()).thenReturn("test_subject");
        when(mockUser.getPrivilegesByApplication(mockApplication)).thenReturn(mockPrivileges);

        // First call to the method - should execute the method logic and add the AccessRule set
        accessRuleService.getAccessRulesForUserAndApp(mockUser, mockApplication);
        verify(mockUser, times(1)).getPrivilegesByApplication(mockApplication);

        // Second call to the method with the same arguments - should hit the cache
        accessRuleService.getAccessRulesForUserAndApp(mockUser, mockApplication);
        verify(mockUser, times(1)).getPrivilegesByApplication(mockApplication);
    }

    @Test
    public void testMergedRulesCacheEvict() {
        Cache cache = cacheManager.getCache("mergedRulesCache");

        // Call the method to store the result in the cache
        Set<Privilege> mockPrivileges = new HashSet<>();
        when(mockUser.getSubject()).thenReturn("test_subject");
        when(mockUser.getPrivilegesByApplication(mockApplication)).thenReturn(mockPrivileges);

        // First call to the method - should execute the method logic and add the AccessRule set
        accessRuleService.getAccessRulesForUserAndApp(mockUser, mockApplication);
        assertThat(cache.get("test_subject")).isNotNull();

        // Evict the test_subject from the cache
        accessRuleService.evictFromMergedAccessRuleCache("test_subject");
        assertThat(cache.get("test_subject")).isNull();
    }

    @Test
    public void testCachedPreProcessAccessRules() {
        when(mockUser.getSubject()).thenReturn("test_subject");
        Set<Privilege> mockedPrivileges = new HashSet<>();
        accessRuleService.cachedPreProcessAccessRules(mockUser, mockedPrivileges);

        Cache preProcessedAccessRules = cacheManager.getCache("preProcessedAccessRules");
        assertThat(preProcessedAccessRules).isNotNull();

        // Verify the cache contains test_subject cache entry
        assertThat(preProcessedAccessRules.get("test_subject")).isNotNull();
    }

    @Test
    public void testCacheEvictPreProcessAccessRules() {
        when(mockUser.getSubject()).thenReturn("test_subject");

        Set<Privilege> mockedPrivileges = new HashSet<>();
        accessRuleService.cachedPreProcessAccessRules(mockUser, mockedPrivileges);

        Cache preProcessedAccessRules = cacheManager.getCache("preProcessedAccessRules");
        assertThat(preProcessedAccessRules).isNotNull();

        // Verify the cache contains test_subject cache entry
        assertThat(preProcessedAccessRules.get("test_subject")).isNotNull();

        accessRuleService.evictFromPreProcessedAccessRules("test_subject");
        assertThat(preProcessedAccessRules.get("test_subject")).isNull();
    }

    @Test
    public void testMergedTemplateCache() {
        when(mockUser.getSubject()).thenReturn("test_subject");

        Set<Privilege> mockPrivileges = new HashSet<>();
        when(mockUser.getPrivilegesByApplication(mockApplication)).thenReturn(mockPrivileges);

        // First call to the method - should execute the method logic and add the mergeTemplate
        userService.mergeTemplate(mockUser, mockApplication);
        verify(mockUser, times(1)).getPrivilegesByApplication(mockApplication);

        // Second call to the method - should be handled by the cache and getPrivilegesByApplication should not be called again
        userService.mergeTemplate(mockUser, mockApplication);
        verify(mockUser, times(1)).getPrivilegesByApplication(mockApplication);
    }

    @Test
    public void testCacheEvictMergedTemplateCache() {
        when(mockUser.getSubject()).thenReturn("test_subject");

        Set<Privilege> mockPrivileges = new HashSet<>();
        when(mockUser.getPrivilegesByApplication(mockApplication)).thenReturn(mockPrivileges);

        // First call to the method - should execute the method logic and add the mergeTemplate
        userService.mergeTemplate(mockUser, mockApplication);
        verify(mockUser, times(1)).getPrivilegesByApplication(mockApplication);

        Cache cache = cacheManager.getCache("mergedTemplateCache");
        assertThat(cache.get("test_subject")).isNotNull();

        userService.evictFromCache("test_subject");
        assertThat(cache.get("test_subject")).isNull();
    }

    @Test
    public void testCacheEvictionService() {
        when(mockUser.getSubject()).thenReturn("test_subject");
        Set<Privilege> mockPrivileges = new HashSet<>();

        when(mockUser.getPrivilegesByApplication(mockApplication)).thenReturn(mockPrivileges);

        // Initialize mergedTemplateCache
        userService.mergeTemplate(mockUser, mockApplication);

        // Verify the cache contains mergedTemplateCache has test_subject
        Cache mergedTemplateCache = cacheManager.getCache("mergedTemplateCache");
        assertThat(mergedTemplateCache.get("test_subject")).isNotNull();

        // Initialize preProcessedAccessRules
        when(mockUser.getSubject()).thenReturn("test_subject");
        accessRuleService.cachedPreProcessAccessRules(mockUser, mockPrivileges);
        Cache preProcessedAccessRules = cacheManager.getCache("preProcessedAccessRules");

        // Verify the cache contains test_subject cache entry
        assertThat(preProcessedAccessRules).isNotNull();
        assertThat(preProcessedAccessRules.get("test_subject")).isNotNull();

        when(mockUser.getSubject()).thenReturn("test_subject");
        when(mockUser.getPrivilegesByApplication(mockApplication)).thenReturn(mockPrivileges);

        // First call to the method - should execute the method logic and add the AccessRule set
        accessRuleService.getAccessRulesForUserAndApp(mockUser, mockApplication);
        Cache mergedRulesCache = cacheManager.getCache("mergedRulesCache");
        assertThat(mergedRulesCache).isNotNull();
        assertThat(mergedRulesCache.get("test_subject")).isNotNull();

        cacheEvictionService.evictCache(mockUser);
        assertThat(mergedRulesCache.get("test_subject")).isNull();
        assertThat(preProcessedAccessRules.get("test_subject")).isNull();
        assertThat(mergedTemplateCache.get("test_subject")).isNull();
    }

    @Configuration
    @EnableCaching
    public static class TestCacheConfig {

        @Bean
        public CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("mergedRulesCache", "preProcessedAccessRules", "mergedTemplateCache");
        }
    }

}
