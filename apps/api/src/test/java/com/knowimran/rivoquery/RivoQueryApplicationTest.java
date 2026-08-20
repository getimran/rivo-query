package com.knowimran.rivoquery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

class RivoQueryApplicationTest {

    @Test
    void declaresTheSpringBootApplicationEntryPoint() {
        assertThat(RivoQueryApplication.class).hasAnnotation(SpringBootApplication.class);
    }
}
