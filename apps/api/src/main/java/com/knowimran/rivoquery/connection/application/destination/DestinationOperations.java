package com.knowimran.rivoquery.connection.application.destination;

import com.knowimran.rivoquery.connection.application.destination.DestinationCapabilityAuthority.ValidatedDestination;

public interface DestinationOperations {
    ValidatedDestination validate(DestinationRequest request);
}
