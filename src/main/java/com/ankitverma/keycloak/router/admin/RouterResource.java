package com.ankitverma.keycloak.router.admin;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.keycloak.Config;
import org.keycloak.models.AdminRoles;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.services.managers.AppAuthManager;
import org.keycloak.services.managers.AuthenticationManager;

import com.ankitverma.keycloak.router.RealmRouter;
import com.ankitverma.keycloak.router.RouterConfig;

/**
 * Read-only status endpoint: {@code GET /realms/master/realm-router} with a master-realm admin bearer token.
 */
public class RouterResource {

    private final KeycloakSession session;

    public RouterResource(KeycloakSession session) {
        this.session = session;
    }

    @GET
    @Path("")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> status() {
        requireMasterAdmin();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("catalog", RouterConfig.DEFAULT_KEY);
        result.put("datasources", RouterConfig.tenantKeys());
        result.put("placementHeader", RouterConfig.placementHeader());
        result.put("defaultPlacement", RouterConfig.defaultPlacement());
        result.put("realms", RealmRouter.snapshotByName());
        return result;
    }

    private void requireMasterAdmin() {
        RealmModel realm = session.getContext().getRealm();
        if (realm == null || !Config.getAdminRealm().equals(realm.getName())) {
            throw new ForbiddenException("Only available on the " + Config.getAdminRealm() + " realm");
        }
        AuthenticationManager.AuthResult auth = new AppAuthManager.BearerTokenAuthenticator(session).authenticate();
        if (auth == null || auth.getUser() == null) {
            throw new NotAuthorizedException("Bearer");
        }
        RoleModel admin = realm.getRole(AdminRoles.ADMIN);
        if (admin == null || !auth.getUser().hasRole(admin)) {
            throw new ForbiddenException("Requires the '" + AdminRoles.ADMIN + "' role");
        }
    }
}
