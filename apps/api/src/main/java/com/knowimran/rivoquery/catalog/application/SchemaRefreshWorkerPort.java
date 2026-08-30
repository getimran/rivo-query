package com.knowimran.rivoquery.catalog.application;

public interface SchemaRefreshWorkerPort {
    boolean claim(String operationId);

    void complete(String operationId);

    void fail(String operationId);
}
