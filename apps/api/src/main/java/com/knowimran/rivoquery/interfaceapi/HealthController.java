package com.knowimran.rivoquery.interfaceapi;

import com.knowimran.rivoquery.application.health.ReadinessCheck;
import com.knowimran.rivoquery.interfaceapi.generated.api.HealthApi;
import com.knowimran.rivoquery.interfaceapi.generated.model.HealthResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController implements HealthApi {

    private final ReadinessCheck readinessCheck;

    public HealthController(ReadinessCheck readinessCheck) {
        this.readinessCheck = readinessCheck;
    }

    @Override
    public ResponseEntity<HealthResponse> getLiveness(String ignoredRequestId) {
        return ResponseEntity.ok(up());
    }

    @Override
    public ResponseEntity<HealthResponse> getReadiness(String ignoredRequestId) {
        readinessCheck.verifyReady();
        return ResponseEntity.ok(up());
    }

    private HealthResponse up() {
        return new HealthResponse(HealthResponse.StatusEnum.UP);
    }
}
