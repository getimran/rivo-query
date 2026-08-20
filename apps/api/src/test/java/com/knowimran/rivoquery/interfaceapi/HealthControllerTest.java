package com.knowimran.rivoquery.interfaceapi;

import static org.assertj.core.api.Assertions.assertThat;
import com.knowimran.rivoquery.application.health.ReadinessCheck;
import com.knowimran.rivoquery.interfaceapi.generated.model.HealthResponse;
import org.junit.jupiter.api.Test;

class HealthControllerTest {

    private final RecordingReadinessCheck readinessCheck = new RecordingReadinessCheck();
    private final HealthController controller = new HealthController(readinessCheck);

    @Test
    void livenessReturnsUpWithoutCallingADownstreamDependency() {
        assertThat(controller.getLiveness(null).getBody())
                .extracting(HealthResponse::getStatus)
                .isEqualTo(HealthResponse.StatusEnum.UP);
        assertThat(readinessCheck.invocations).isZero();
    }

    @Test
    void readinessRequiresTheControlDatabaseProbe() {
        assertThat(controller.getReadiness(null).getBody())
                .extracting(HealthResponse::getStatus)
                .isEqualTo(HealthResponse.StatusEnum.UP);
        assertThat(readinessCheck.invocations).isEqualTo(1);
    }

    private static final class RecordingReadinessCheck implements ReadinessCheck {

        private int invocations;

        @Override
        public void verifyReady() {
            invocations++;
        }
    }
}
