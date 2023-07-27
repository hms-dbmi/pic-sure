package edu.harvard.dbmi.avillach.service;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;

import com.github.tomakehurst.wiremock.junit.WireMockRule;

import edu.harvard.dbmi.avillach.PicSureWarInit;
import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.dbmi.avillach.data.repository.ResourceRepository;

public class SystemServiceTest {

	@Rule
	public WireMockRule wireMockRule = new WireMockRule(0);

	private int port;
	
	private void tokenIntrospectionStub(int status, String tokenIntrospectionResult) {
		stubFor(post(urlEqualTo("/introspection_endpoint"))
				.willReturn(aResponse()
						.withStatus(status)
						.withHeader("Content-Type", "application/json")
						.withBody("{\"active\":" + tokenIntrospectionResult + ",\"sub\":\"TEST_USER\"}")));
	}

	private void resourceStub(int status) {
		stubFor(post(urlEqualTo("/resource"))
				.willReturn(aResponse()
						.withStatus(status)
						.withHeader("Content-Type", "application/json")
						.withBody("{\"info\":\"foobar\"}")));
	}

	
	private SystemService basicService() {
		SystemService service = new SystemService();
		service.picSureWarInit = mock(PicSureWarInit.class);
		when(service.picSureWarInit.getToken_introspection_token()).thenReturn("TOKEN");
		when(service.picSureWarInit.getToken_introspection_url()).thenReturn(
				"http://localhost:" + port + "/introspection_endpoint");
		service.init();
		return service;
	}

	@Test
	public void testStatusDegradedIfResourceRepositoryReturnsNull() {
		SystemService service = basicService();
		service.resourceRepo = mock(ResourceRepository.class);
		when(service.resourceRepo.list()).thenReturn(null);
		String status = service.status();
		assertEquals(status, SystemService.ONE_OR_MORE_COMPONENTS_DEGRADED);
	}
	
	@Test
	public void testStatusDegradedIfResourcesNotDefined() {
		SystemService service = basicService();
		service.resourceRepo = mock(ResourceRepository.class);
		when(service.resourceRepo.list()).thenReturn(new ArrayList<Resource>());
		String status = service.status();
		assertEquals(status, SystemService.ONE_OR_MORE_COMPONENTS_DEGRADED);
	}

	@Test
	public void testStatusDegradedIfResourceRepoThrowsException() {
		SystemService service = basicService();
		service.resourceRepo = mock(ResourceRepository.class);
		when(service.resourceRepo.list()).thenThrow(RuntimeException.class);
		String status = service.status();
		assertEquals(status, SystemService.ONE_OR_MORE_COMPONENTS_DEGRADED);
	}

	@Test
	public void testThrowsExceptionOnStartupIfTokenIntrospectionTokenNotConfigured() {
		SystemService service = basicService();
		when(service.picSureWarInit.getToken_introspection_token()).thenReturn(null);
		try{
			service.token_introspection_token = null;
			service.init();
		}catch(Exception e) {
			return;
		}
		assertTrue("Expected an exception to be thrown.", false);
	}

	@Test
	public void testThrowsExceptionOnStartupIfTokenIntrospectionUrlNotConfigured() {
		SystemService service = basicService();
		when(service.picSureWarInit.getToken_introspection_url()).thenReturn(null);
		try{
			service.token_introspection_url = null;
			service.init();
		}catch(Exception e) {
			return;
		}
		assertTrue("Expected an exception to be thrown.", false);
	}

	@Test
	public void testStatusDegradedIfTokenIntrospectionFails() {
		tokenIntrospectionStub(500, "false");
		SystemService service = basicService();
		service.resourceRepo = mock(ResourceRepository.class);
		when(service.resourceRepo.list()).thenReturn(List.of(mock(Resource.class)));
		String status = service.status();
		assertEquals(status, SystemService.ONE_OR_MORE_COMPONENTS_DEGRADED);
	}

	@Test
	public void testStatusDegradedIfTokenIntrospectionFailsDueToBadToken() {
		tokenIntrospectionStub(401, "false");
		SystemService service = basicService();
		service.resourceRepo = mock(ResourceRepository.class);
		when(service.resourceRepo.list()).thenReturn(List.of(mock(Resource.class)));
		String status = service.status();
		assertEquals(status, SystemService.ONE_OR_MORE_COMPONENTS_DEGRADED);
	}

	@Test
	public void testStatusDegradedIfResourceUnreachable() {
		tokenIntrospectionStub(200, "true");
		resourceStub(500);
		SystemService service = basicService();
		service.resourceRepo = mock(ResourceRepository.class);
		Resource resource = mock(Resource.class);
		when(resource.getResourceRSPath()).thenReturn("http://localhost:"+port+"/resource");
		when(service.resourceRepo.list()).thenReturn(List.of(resource));
		String status = service.status();
		assertEquals(status, SystemService.ONE_OR_MORE_COMPONENTS_DEGRADED);
	}

	@Test
	public void testStatusRunningIfNothingIsWrong() {
		tokenIntrospectionStub(200, "true");
		resourceStub(200);
		SystemService service = basicService();
		service.resourceRepo = mock(ResourceRepository.class);
		Resource resource = mock(Resource.class);
		when(resource.getResourceRSPath()).thenReturn("http://localhost:"+port+"/resource");
		when(service.resourceRepo.list()).thenReturn(List.of(resource));
		String status = service.status();
		assertEquals(status, SystemService.ONE_OR_MORE_COMPONENTS_DEGRADED);
	}

	@Test
	public void testServiceOnlyChecksStatusOncePerMaxTestFrequency() throws Exception {
		tokenIntrospectionStub(200, "true");
		resourceStub(200);
		SystemService service = basicService();
		service.resourceRepo = mock(ResourceRepository.class);
		Resource resource = mock(Resource.class);
		when(resource.getResourceRSPath()).thenReturn("http://localhost:"+port+"/resource");
		when(service.resourceRepo.list()).thenReturn(List.of(resource));
		String status = service.status();
		assertEquals(status, SystemService.ONE_OR_MORE_COMPONENTS_DEGRADED);
		SystemService.max_test_frequency = 200;
		Thread.sleep(100);
		status = service.status();
		assertEquals(status, SystemService.ONE_OR_MORE_COMPONENTS_DEGRADED);
		verify(service.resourceRepo, atMost(1)).list();
	}
	
	@Test
	public void testServiceRechecksStatusAfterMaxTestFrequency() throws Exception {
		tokenIntrospectionStub(200, "true");
		resourceStub(200);
		SystemService service = basicService();
		service.resourceRepo = mock(ResourceRepository.class);
		Resource resource = mock(Resource.class);
		when(resource.getResourceRSPath()).thenReturn("http://localhost:"+port+"/resource");
		when(service.resourceRepo.list()).thenReturn(List.of(resource));
		String status = service.status();
		assertEquals(status, SystemService.ONE_OR_MORE_COMPONENTS_DEGRADED);
		SystemService.max_test_frequency = 100;
		Thread.sleep(200);
		status = service.status();
		assertEquals(status, SystemService.ONE_OR_MORE_COMPONENTS_DEGRADED);
		verify(service.resourceRepo, atLeast(2)).list();
	}
}
