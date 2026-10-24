package com.ankitverma.keycloak.router.storage;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;

import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

import com.ankitverma.keycloak.router.RealmRouter;

/**
 * Wraps a {@link RealmModel} so that {@link RealmModel#setName(String)} keeps {@link RealmRouter}'s catalog row
 * and {@code BY_NAME} cache in sync. Nothing else watches for realm renames today, so without this a renamed
 * realm stays resolvable only by its old name until the process restarts.
 */
final class RealmNameSyncProxy implements InvocationHandler {

    private final RealmModel delegate;
    private final KeycloakSession session;
    private final String key;

    private RealmNameSyncProxy(RealmModel delegate, KeycloakSession session, String key) {
        this.delegate = delegate;
        this.session = session;
        this.key = key;
    }

    static RealmModel wrap(RealmModel realm, KeycloakSession session, String key) {
        if (realm == null) {
            return null;
        }
        Class<?>[] interfaces = RoutingProxy.interfacesOf(RealmModel.class, realm.getClass());
        return (RealmModel) Proxy.newProxyInstance(realm.getClass().getClassLoader(), interfaces,
                new RealmNameSyncProxy(realm, session, key));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (!"setName".equals(method.getName()) || args == null || args.length != 1) {
            return call(method, args);
        }
        String oldName = delegate.getName();
        String newName = (String) args[0];
        Object result = call(method, args);
        if (!Objects.equals(oldName, newName)) {
            RealmRouter.renameRealm(session, delegate.getId(), oldName, newName, key);
        }
        return result;
    }

    private Object call(Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
