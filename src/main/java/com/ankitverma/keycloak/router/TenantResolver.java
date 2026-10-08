package com.ankitverma.keycloak.router;

import org.keycloak.events.Event;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientScopeModel;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleContainerModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserSessionModel;

/**
 * Decides which datasource a storage call belongs to.
 * <ol>
 *   <li>a realm-bearing argument (realm, client, client scope, role, user session, event);</li>
 *   <li>the datasource explicitly selected on the session ({@link TenantContext});</li>
 *   <li>the realm of the current request ({@link KeycloakContext#getRealm()});</li>
 *   <li>the catalog database.</li>
 * </ol>
 */
public final class TenantResolver {

    private TenantResolver() {
    }

    public static String resolve(KeycloakSession session, Object[] args) {
        if (args != null) {
            for (Object arg : args) {
                String key = fromArgument(session, arg);
                if (key != null) {
                    return key;
                }
            }
        }
        return fromContext(session);
    }

    public static String fromArgument(KeycloakSession session, Object arg) {
        if (arg instanceof RealmModel realm) {
            return forRealm(session, realm);
        }
        if (arg instanceof ClientModel client) {
            return forRealm(session, client.getRealm());
        }
        if (arg instanceof ClientScopeModel scope) {
            return forRealm(session, scope.getRealm());
        }
        if (arg instanceof UserSessionModel userSession) {
            return forRealm(session, userSession.getRealm());
        }
        if (arg instanceof AuthenticatedClientSessionModel clientSession) {
            return forRealm(session, clientSession.getRealm());
        }
        if (arg instanceof RoleModel role) {
            RoleContainerModel container = role.getContainer();
            if (container instanceof RealmModel realm) {
                return forRealm(session, realm);
            }
            if (container instanceof ClientModel client) {
                return forRealm(session, client.getRealm());
            }
            return null;
        }
        if (arg instanceof Event event) {
            return RealmRouter.keyForRealmId(session, event.getRealmId());
        }
        if (arg instanceof AdminEvent event) {
            return RealmRouter.keyForRealmId(session, event.getRealmId());
        }
        return null;
    }

    public static String forRealm(KeycloakSession session, RealmModel realm) {
        return realm == null ? null : RealmRouter.keyForRealmId(session, realm.getId());
    }

    public static String fromContext(KeycloakSession session) {
        String explicit = TenantContext.current(session);
        if (explicit != null) {
            return explicit;
        }
        KeycloakContext context = session.getContext();
        RealmModel realm = context == null ? null : context.getRealm();
        if (realm != null) {
            return forRealm(session, realm);
        }
        return RouterConfig.DEFAULT_KEY;
    }
}
