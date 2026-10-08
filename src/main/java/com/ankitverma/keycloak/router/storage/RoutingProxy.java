package com.ankitverma.keycloak.router.storage;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import jakarta.persistence.EntityManager;

import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.provider.Provider;

import org.jboss.logging.Logger;

import com.ankitverma.keycloak.router.RouterConfig;
import com.ankitverma.keycloak.router.TenantContext;
import com.ankitverma.keycloak.router.TenantResolver;

/**
 * Generic per-call router in front of a Keycloak storage provider.
 * <p>
 * Every call is resolved to a datasource key ({@link TenantResolver}); the stock Keycloak JPA provider for that
 * datasource is created lazily (with the datasource's entity manager) and the call is delegated to it while the
 * {@link TenantContext} is set, so nested storage calls made by the delegate stay on the same database.
 * <p>
 * The proxy implements every interface of the delegate class (Keycloak casts, for example, the user provider to
 * {@code UserCredentialStore} and the realm provider to {@code ClientProvider}).
 * <p>
 * Methods listed in {@code fanOut} run against every datasource and merge their results. Methods present in
 * {@code overrides} are fully handled by the override.
 */
public final class RoutingProxy<T extends Provider> implements InvocationHandler {

    private static final Logger LOG = Logger.getLogger(RoutingProxy.class);

    @FunctionalInterface
    public interface DelegateFactory<T> {
        T create(KeycloakSession session, EntityManager entityManager);
    }

    @FunctionalInterface
    public interface MethodOverride<T extends Provider> {
        Object invoke(RoutingProxy<T> proxy, Method method, Object[] args) throws Throwable;
    }

    private final KeycloakSession session;
    private final Class<T> type;
    private final DelegateFactory<T> factory;
    private final Set<String> fanOut;
    private final Map<String, MethodOverride<T>> overrides;
    private final Map<String, T> delegates = new LinkedHashMap<>();

    private RoutingProxy(KeycloakSession session, Class<T> type, DelegateFactory<T> factory, Set<String> fanOut,
                         Map<String, MethodOverride<T>> overrides) {
        this.session = session;
        this.type = type;
        this.factory = factory;
        this.fanOut = fanOut;
        this.overrides = overrides;
    }

    public static <T extends Provider> T create(KeycloakSession session, Class<T> type, Class<? extends T> delegateClass,
                                                 DelegateFactory<T> factory) {
        return create(session, type, delegateClass, factory, Set.of(), Map.of());
    }

    public static <T extends Provider> T create(KeycloakSession session, Class<T> type, Class<? extends T> delegateClass,
                                                 DelegateFactory<T> factory, Set<String> fanOut) {
        return create(session, type, delegateClass, factory, fanOut, Map.of());
    }

    public static <T extends Provider> T create(KeycloakSession session, Class<T> type, Class<? extends T> delegateClass,
                                                 DelegateFactory<T> factory, Set<String> fanOut,
                                                 Map<String, MethodOverride<T>> overrides) {
        RoutingProxy<T> handler = new RoutingProxy<>(session, type, factory, fanOut, overrides);
        Class<?>[] interfaces = interfacesOf(type, delegateClass);
        return type.cast(Proxy.newProxyInstance(delegateClass.getClassLoader(), interfaces, handler));
    }

    /** The SPI interface plus every public interface implemented by the delegate class and its superclasses. */
    static Class<?>[] interfacesOf(Class<?> type, Class<?> delegateClass) {
        Set<Class<?>> interfaces = new LinkedHashSet<>();
        interfaces.add(type);
        for (Class<?> c = delegateClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                if (Modifier.isPublic(i.getModifiers())) {
                    interfaces.add(i);
                }
            }
        }
        return interfaces.toArray(new Class<?>[0]);
    }

    /** @return the handler behind a proxy created by this class, or {@code null} for any other object. */
    @SuppressWarnings("unchecked")
    public static <T extends Provider> RoutingProxy<T> handlerOf(Object proxy) {
        if (proxy != null && Proxy.isProxyClass(proxy.getClass())) {
            InvocationHandler handler = Proxy.getInvocationHandler(proxy);
            if (handler instanceof RoutingProxy<?> routing) {
                return (RoutingProxy<T>) routing;
            }
        }
        return null;
    }

    public KeycloakSession session() {
        return session;
    }

    /** The stock provider bound to the given datasource, created on first use. */
    public T delegate(String key) {
        T delegate = delegates.get(key);
        if (delegate == null) {
            delegate = TenantContext.with(session, key, () -> {
                EntityManager em = session.getProvider(JpaConnectionProvider.class).getEntityManager();
                return factory.create(session, em);
            });
            delegates.put(key, delegate);
        }
        return delegate;
    }

    /** Runs {@code body} against the delegate of {@code key} with the tenant context set. */
    public <R> R on(String key, Function<T, R> body) {
        return TenantContext.with(session, key, () -> body.apply(delegate(key)));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return invokeObjectMethod(proxy, method, args);
        }
        String name = method.getName();
        if ("close".equals(name) && (args == null || args.length == 0)) {
            closeAll();
            return null;
        }
        MethodOverride<T> override = overrides.get(name);
        if (override != null) {
            return override.invoke(this, method, args);
        }
        if (fanOut.contains(name)) {
            return fanOut(method, args);
        }
        return call(TenantResolver.resolve(session, args), method, args);
    }

    public Object call(String key, Method method, Object[] args) throws Throwable {
        T delegate = delegate(key);
        Object previous = session.getAttribute(TenantContext.ATTRIBUTE);
        session.setAttribute(TenantContext.ATTRIBUTE, key);
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        } finally {
            if (previous == null) {
                session.removeAttribute(TenantContext.ATTRIBUTE);
            } else {
                session.setAttribute(TenantContext.ATTRIBUTE, previous);
            }
        }
    }

    private Object fanOut(Method method, Object[] args) throws Throwable {
        Class<?> returnType = method.getReturnType();
        List<Object> results = new ArrayList<>();
        Throwable firstFailure = null;
        int failures = 0;
        for (String key : RouterConfig.allKeys()) {
            try {
                Object result = call(key, method, args);
                if (Stream.class.isAssignableFrom(returnType) && result != null) {
                    // Materialize inside the tenant context; the merged stream is consumed later.
                    result = ((Stream<?>) result).toList();
                }
                results.add(result);
            } catch (Throwable t) {
                failures++;
                if (firstFailure == null) {
                    firstFailure = t;
                }
                LOG.warnf(t, "%s.%s failed on datasource '%s'; continuing with the remaining datasources",
                        type.getSimpleName(), method.getName(), key);
            }
        }
        if (results.isEmpty() && firstFailure != null) {
            throw firstFailure;
        }
        if (failures > 0) {
            LOG.warnf("%s.%s: %d datasource(s) failed, result is partial", type.getSimpleName(), method.getName(), failures);
        }
        return merge(returnType, results);
    }

    @SuppressWarnings("unchecked")
    private static Object merge(Class<?> returnType, List<Object> results) {
        if (returnType == void.class || returnType == Void.class) {
            return null;
        }
        if (Stream.class.isAssignableFrom(returnType)) {
            return results.stream().filter(r -> r != null).flatMap(r -> ((List<Object>) r).stream());
        }
        if (returnType == int.class || returnType == Integer.class) {
            return results.stream().filter(r -> r != null).mapToInt(r -> (Integer) r).sum();
        }
        if (returnType == long.class || returnType == Long.class) {
            return results.stream().filter(r -> r != null).mapToLong(r -> (Long) r).sum();
        }
        if (returnType == boolean.class || returnType == Boolean.class) {
            return results.stream().anyMatch(Boolean.TRUE::equals);
        }
        if (Map.class.isAssignableFrom(returnType)) {
            Map<Object, Object> merged = new LinkedHashMap<>();
            results.stream().filter(r -> r != null).forEach(r -> merged.putAll((Map<Object, Object>) r));
            return merged;
        }
        if (Collection.class.isAssignableFrom(returnType)) {
            List<Object> merged = new ArrayList<>();
            results.stream().filter(r -> r != null).forEach(r -> merged.addAll((Collection<Object>) r));
            return merged;
        }
        return results.stream().filter(r -> r != null).findFirst().orElse(null);
    }

    private void closeAll() {
        for (Map.Entry<String, T> entry : delegates.entrySet()) {
            try {
                TenantContext.run(session, entry.getKey(), entry.getValue()::close);
            } catch (RuntimeException e) {
                LOG.warnf(e, "Failed to close %s delegate for datasource '%s'", type.getSimpleName(), entry.getKey());
            }
        }
        delegates.clear();
    }

    private Object invokeObjectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "Routing" + type.getSimpleName() + delegates.keySet();
            default -> throw new UnsupportedOperationException(method.getName());
        };
    }
}
