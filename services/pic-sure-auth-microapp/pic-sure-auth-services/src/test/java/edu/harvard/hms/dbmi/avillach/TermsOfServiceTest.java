package edu.harvard.hms.dbmi.avillach;

import edu.harvard.hms.dbmi.avillach.auth.data.entity.TermsOfService;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.TermsOfServiceRepository;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.UserRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.TermsOfServiceService;
import org.junit.Before;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class TermsOfServiceTest {

    @InjectMocks
    TermsOfServiceService cut = new TermsOfServiceService();

    @Mock
    UserRepository userRepo = mock(UserRepository.class);

    @Mock
    TermsOfServiceRepository termsOfServiceRepo = mock(TermsOfServiceRepository.class);

    String latestContent = "Latest Content";
    TermsOfService TOS;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(termsOfServiceRepo.getLatest()).thenReturn(TOS);
        doAnswer(new Answer<Void>() {
            public Void answer(InvocationOnMock invocation) {
                TOS = invocation.getArgument(0);
                return null;
            }
        }).when(termsOfServiceRepo).persist(any(TermsOfService.class));
    }

  /*  @Test
    public void testGetLatest(){
//        TOS.setContent(latestContent);
//        String tos = cut.getLatest();
  //      assertNull(tos);
        TOS = new TermsOfService().setContent(latestContent);
        String tos = cut.getLatest();
        assertEquals(latestContent, tos);
    }

    @Test
    public void testUpdateTOS(){
        String content = "new Content";
        cut.updateTermsOfService(content);
        String updated = cut.getLatest();
        assertNotNull(updated);
        assertEquals(content, updated);
    }*/

}
