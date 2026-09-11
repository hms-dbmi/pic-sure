package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.model.request.AccessRuleUpdateRequest;
import edu.harvard.hms.dbmi.avillach.auth.repository.AccessRuleRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every administrative update loads its row by the identifier in the request record, so naming a row that does not exist must be rejected rather than silently creating one.
 */
class UnknownIdentifierRejectionTest {

    @Test
    void everyUpdateRejectsAnIdentifierThatDoesNotExist() {
        AccessRuleRepository accessRules = mock(AccessRuleRepository.class);
        UUID missing = UUID.randomUUID();
        when(accessRules.findById(missing)).thenReturn(Optional.empty());

        assertThrows(
            IllegalArgumentException.class,
            () -> new AccessRuleService(accessRules, "[]")
                .updateFrom(List.of(new AccessRuleUpdateRequest(missing, "x", null, null, null, null, null, null, null, null, null, null)))
        );
        assertTrue(true);
    }
}
