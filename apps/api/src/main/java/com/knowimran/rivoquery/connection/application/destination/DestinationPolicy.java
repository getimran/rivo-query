package com.knowimran.rivoquery.connection.application.destination;

public interface DestinationPolicy {
    DestinationAssessment assess(DestinationRequest request);
}
