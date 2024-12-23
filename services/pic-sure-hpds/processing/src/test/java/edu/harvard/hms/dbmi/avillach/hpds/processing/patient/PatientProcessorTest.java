package edu.harvard.hms.dbmi.avillach.hpds.processing.patient;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.processing.AbstractProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.AsyncResult;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.TreeSet;

@EnableAutoConfiguration
@SpringBootTest(classes = PatientProcessor.class)
class PatientProcessorTest {

    @MockBean
    AbstractProcessor abstractProcessor;

    @Autowired
    PatientProcessor subject;

    @Test
    void shouldProcessPatientQuery() {
        Query q = new Query();
        q.setId("frank");
        q.setPicSureId("frank");
        q.setExpectedResultType(ResultType.PATIENTS);
        AsyncResult writeToThis = Mockito.mock(AsyncResult.class);
        Mockito.when(abstractProcessor.getPatientSubsetForQuery(q))
            .thenReturn(new TreeSet<>(List.of(1, 2, 42)));

        subject.runQuery(q, writeToThis);

        Mockito.verify(writeToThis, Mockito.times(1))
            .appendResults(Mockito.argThat(strings ->
                strings.size() == 3 &&
                strings.get(0)[0].equals("1") &&
                strings.get(1)[0].equals("2") &&
                strings.get(2)[0].equals("42"))
            );
    }
}