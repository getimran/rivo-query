package com.knowimran.rivoquery.catalog.application;

import com.knowimran.rivoquery.catalog.SchemaRefreshOperation;
import java.util.Optional;

public interface SchemaRefreshOperationRepository {
    void insert(SchemaRefreshOperation operation);

    Optional<SchemaRefreshOperation> findById(String operationId);

    int updateState(String operationId, String state);
}
