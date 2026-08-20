package com.knowimran.rivoquery.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowimran.rivoquery.application.health.ControlDatabaseUnavailableException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

class ControlDatabaseReadinessProbeTest {

    @Test
    void performsASingleBoundedHeartbeatLookup() {
        RecordingJdbcObjects jdbcObjects = new RecordingJdbcObjects(true);

        assertThatNoException()
                .isThrownBy(() -> new ControlDatabaseReadinessProbe(
                                new CallbackJdbcTemplate(jdbcObjects.connection()))
                        .verifyReady());

        assertThat(jdbcObjects.query)
                .isEqualTo("SELECT 1 FROM platform_heartbeat WHERE singleton_id = 1 LIMIT 1");
        assertThat(jdbcObjects.queryTimeoutSeconds)
                .isEqualTo(ControlDatabaseReadinessProbe.QUERY_TIMEOUT_SECONDS);
        assertThat(jdbcObjects.maxRows).isEqualTo(1);
        assertThat(jdbcObjects.executeCount).isEqualTo(1);
    }

    @Test
    void missingHeartbeatIsUnavailable() {
        RecordingJdbcObjects jdbcObjects = new RecordingJdbcObjects(false);

        assertThatThrownBy(() -> new ControlDatabaseReadinessProbe(
                                new CallbackJdbcTemplate(jdbcObjects.connection()))
                        .verifyReady())
                .isInstanceOf(ControlDatabaseUnavailableException.class)
                .hasMessage("Control database readiness check failed");
    }

    @Test
    void dataAccessFailureIsMappedWithoutCopyingItsMessage() {
        JdbcTemplate jdbcTemplate = new FailingJdbcTemplate();

        assertThatThrownBy(() -> new ControlDatabaseReadinessProbe(jdbcTemplate).verifyReady())
                .isInstanceOf(ControlDatabaseUnavailableException.class)
                .hasMessage("Control database readiness check failed")
                .hasCauseInstanceOf(DataAccessResourceFailureException.class);
    }

    private static final class CallbackJdbcTemplate extends JdbcTemplate {

        private final Connection connection;

        private CallbackJdbcTemplate(Connection connection) {
            this.connection = connection;
        }

        @Override
        public <T> T execute(ConnectionCallback<T> action) {
            try {
                return action.doInConnection(connection);
            } catch (SQLException exception) {
                throw new AssertionError(exception);
            }
        }
    }

    private static final class FailingJdbcTemplate extends JdbcTemplate {

        @Override
        public <T> T execute(ConnectionCallback<T> action) {
            throw new DataAccessResourceFailureException("password=do-not-expose");
        }
    }

    private static final class RecordingJdbcObjects {

        private final boolean heartbeatPresent;
        private String query;
        private int queryTimeoutSeconds;
        private int maxRows;
        private int executeCount;

        private RecordingJdbcObjects(boolean heartbeatPresent) {
            this.heartbeatPresent = heartbeatPresent;
        }

        private Connection connection() {
            ResultSet resultSet = proxy(ResultSet.class, Map.of(
                    "next", (InvocationHandler) (ignoredProxy, ignoredMethod, ignoredArguments) ->
                            heartbeatPresent));
            PreparedStatement statement = proxy(PreparedStatement.class, Map.of(
                    "setQueryTimeout", (InvocationHandler) (ignoredProxy, ignoredMethod, arguments) -> {
                        queryTimeoutSeconds = (int) arguments[0];
                        return null;
                    },
                    "setMaxRows", (InvocationHandler) (ignoredProxy, ignoredMethod, arguments) -> {
                        maxRows = (int) arguments[0];
                        return null;
                    },
                    "executeQuery", (InvocationHandler) (ignoredProxy, ignoredMethod, ignoredArguments) -> {
                        executeCount++;
                        return resultSet;
                    }));
            return proxy(Connection.class, Map.of(
                    "prepareStatement", (InvocationHandler) (ignoredProxy, ignoredMethod, arguments) -> {
                        query = (String) arguments[0];
                        return statement;
                    }));
        }

        private static <T> T proxy(Class<T> type, Map<String, InvocationHandler> handlers) {
            InvocationHandler dispatcher = (proxy, method, arguments) -> {
                InvocationHandler handler = handlers.get(method.getName());
                if (handler != null) {
                    return handler.invoke(proxy, method, arguments);
                }
                return defaultValue(method);
            };
            return type.cast(Proxy.newProxyInstance(
                    type.getClassLoader(), new Class<?>[] {type}, dispatcher));
        }

        private static Object defaultValue(Method method) {
            Class<?> returnType = method.getReturnType();
            if (!returnType.isPrimitive()) {
                return null;
            }
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType == byte.class) {
                return (byte) 0;
            }
            if (returnType == short.class) {
                return (short) 0;
            }
            if (returnType == int.class) {
                return 0;
            }
            if (returnType == long.class) {
                return 0L;
            }
            if (returnType == float.class) {
                return 0F;
            }
            if (returnType == double.class) {
                return 0D;
            }
            if (returnType == char.class) {
                return '\0';
            }
            return null;
        }
    }
}
