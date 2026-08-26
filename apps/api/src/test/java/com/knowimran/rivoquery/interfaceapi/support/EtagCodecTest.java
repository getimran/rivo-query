package com.knowimran.rivoquery.interfaceapi.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowimran.rivoquery.connection.ConnectionVersion;
import org.junit.jupiter.api.Test;

class EtagCodecTest {

    @Test
    void connectionVersionRoundTrips() {
        var version = new ConnectionVersion(7);

        assertThat(EtagCodec.format(version)).isEqualTo("\"7\"");
        assertThat(EtagCodec.parse("\"7\"")).isEqualTo(version);
    }

    @Test
    void longVersionRoundTrips() {
        assertThat(EtagCodec.format(42L)).isEqualTo("\"42\"");
        assertThat(EtagCodec.parseLong("\"42\"")).isEqualTo(42L);
    }

    @Test
    void malformedIfMatchIsRejected() {
        assertThatThrownBy(() -> EtagCodec.parse("7")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EtagCodec.parse("\"not-a-number\"")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EtagCodec.parseLong("\"\"")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EtagCodec.parseLong("weak-etag")).isInstanceOf(IllegalArgumentException.class);
    }
}
