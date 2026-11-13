package edu.harvard.hms.dbmi.avillach.operations.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Boots the whole application on H2 with the result type pass turned on, which no other test does, and checks that the application starts
 * the pass by itself once it is ready. The database holds no rows, so the pass has nothing to write.
 *
 * <p>The test does not sleep to give the pass time. It stops the pass, which waits for the thread of the pass to end, and then reads the
 * line the pass logged on its way out.
 */
@SpringBootTest(properties = "picsure.operations.result-type-backfill.enabled=true")
@ExtendWith(OutputCaptureExtension.class)
@Timeout(60)
class ResultTypeBackfillStartupTest {

    @Autowired
    private ResultTypeBackfill backfill;

    @Test
    void theApplicationStartsOnePassOnceItIsReady(CapturedOutput output) {
        backfill.stop();

        assertThat(backfill.isRunning()).isFalse();
        assertThat(output.getAll()).doesNotContain("Result type fill is disabled")
            .containsPattern("Result type fill (finished|was stopped): 0 rows typed");
    }
}
