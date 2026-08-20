package com.knowimran.rivoquery.application.health;

@FunctionalInterface
public interface ReadinessCheck {

    void verifyReady();
}
