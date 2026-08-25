package com.knowimran.rivoquery.connection.connector;

import com.knowimran.rivoquery.connection.application.Deadline;
import com.knowimran.rivoquery.connection.application.destination.DestinationCapabilityAuthority.ValidatedDestination;

public interface DatabaseConnector {
    ConnectorCapabilities capabilities();

    ConnectorResult test(ValidatedDestination destination, byte[] borrowedSecretMaterial, Deadline deadline);
}
