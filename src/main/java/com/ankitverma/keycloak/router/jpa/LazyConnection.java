package com.ankitverma.keycloak.router.jpa;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

import com.ankitverma.keycloak.router.TenantDataSources;

/**
 * A {@link Connection} that borrows the real connection from the tenant pool on first use.
 * <p>
 * Opening the Hibernate session before the pool hands out (and JTA-enlists) the connection guarantees that
 * Hibernate's transaction synchronization is registered before Agroal's, so at commit time Hibernate flushes
 * first and the connection is handed back before Agroal's own housekeeping runs.
 */
final class LazyConnection implements InvocationHandler {

    private final String key;
    private Connection delegate;
    private boolean closed;

    LazyConnection(String key) {
        this.key = key;
    }

    Connection proxy() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, this);
    }

    boolean acquired() {
        return delegate != null;
    }

    void close() throws SQLException {
        closed = true;
        Connection c = delegate;
        delegate = null;
        if (c != null) {
            c.close();
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        switch (method.getName()) {
            case "close" -> {
                close();
                return null;
            }
            case "isClosed" -> {
                return delegate == null ? closed : delegate.isClosed();
            }
            case "getAutoCommit" -> {
                // JTA-managed connections run with auto-commit off; answering without a round trip keeps the
                // acquisition lazy when Hibernate inspects the connection while opening the session.
                if (delegate == null) {
                    return Boolean.FALSE;
                }
            }
            case "isValid" -> {
                if (delegate == null) {
                    return Boolean.TRUE;
                }
            }
            case "toString" -> {
                return "LazyConnection[" + key + (delegate == null ? ", not acquired]" : ", " + delegate + "]");
            }
            case "hashCode" -> {
                return System.identityHashCode(proxy);
            }
            case "equals" -> {
                return proxy == args[0];
            }
            default -> {
            }
        }
        if (closed) {
            throw new SQLException("Connection for datasource '" + key + "' is closed");
        }
        if (delegate == null) {
            delegate = TenantDataSources.connection(key);
        }
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
