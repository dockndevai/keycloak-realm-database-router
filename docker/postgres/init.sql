-- One database per tenant realm. The catalog database ("keycloak") is created by POSTGRES_DB.
CREATE DATABASE kc_alpha OWNER keycloak;
CREATE DATABASE kc_beta OWNER keycloak;
CREATE DATABASE kc_gamma OWNER keycloak;
