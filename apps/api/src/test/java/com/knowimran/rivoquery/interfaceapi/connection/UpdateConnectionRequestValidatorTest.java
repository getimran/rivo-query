package com.knowimran.rivoquery.interfaceapi.connection;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowimran.rivoquery.interfaceapi.generated.model.ConnectionLimits;
import com.knowimran.rivoquery.interfaceapi.generated.model.EndpointInput;
import com.knowimran.rivoquery.interfaceapi.generated.model.TlsMode;
import com.knowimran.rivoquery.interfaceapi.generated.model.UpdateConnectionRequest;
import com.knowimran.rivoquery.interfaceapi.support.ValidationFailedException;
import org.junit.jupiter.api.Test;

/**
 * Enforces the OpenAPI {@code UpdateConnectionRequest.minProperties: 1} rule
 * generated Java Bean Validation cannot express (see the P2-130 task's own
 * note).
 */
class UpdateConnectionRequestValidatorTest {

    @Test
    void allNullBodyIsRejected() {
        assertThatThrownBy(() -> UpdateConnectionRequestValidator.requireNonEmpty(new UpdateConnectionRequest()))
                .isInstanceOf(ValidationFailedException.class);
    }

    @Test
    void singleFieldIsAccepted() {
        assertThatCode(() -> UpdateConnectionRequestValidator.requireNonEmpty(
                        new UpdateConnectionRequest().name("renamed")))
                .doesNotThrowAnyException();
    }

    @Test
    void tlsModeOnlyIsAccepted() {
        assertThatCode(() -> UpdateConnectionRequestValidator.requireNonEmpty(
                        new UpdateConnectionRequest().tlsMode(TlsMode.VERIFY_FULL)))
                .doesNotThrowAnyException();
    }

    @Test
    void limitsOnlyIsAccepted() {
        assertThatCode(() -> UpdateConnectionRequestValidator.requireNonEmpty(
                        new UpdateConnectionRequest().limits(new ConnectionLimits(30000, 1000))))
                .doesNotThrowAnyException();
    }

    @Test
    void nonNullEndpointIsRejected() {
        assertThatThrownBy(() -> UpdateConnectionRequestValidator.rejectEndpoint(
                        new UpdateConnectionRequest().endpoint(new EndpointInput("169.254.169.254", 5432, "app"))))
                .isInstanceOf(ConnectionEndpointUpdateNotSupportedException.class);
    }

    @Test
    void nullEndpointIsAccepted() {
        assertThatCode(() -> UpdateConnectionRequestValidator.rejectEndpoint(
                        new UpdateConnectionRequest().name("renamed")))
                .doesNotThrowAnyException();
    }
}
