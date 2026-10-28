# Running WMS against auraDOCS

In integrated mode auraDOCS' Keycloak (realm `auraDOCS`, http://localhost:8080) is the identity provider for WMS,
and identity-service reads groups and departments from auraDOCS. Run `deploy/keycloak/auradocs-realm-setup.sh` once
to create the WMS roles and clients in that realm.

The WMS front stays a separate app (dev: `npm run start:auradocs` on http://localhost:4300). auraDOCS links to it and
opens it in a new browser tab; the shared Keycloak session signs the person in without a second login. Only the
backends talk to each other: identity-service reads the auraDOCS directory, and the auraDOCS document panel calls
the WMS workflow API directly with the person's auraDOCS token.

## identity-service

Start it with the `auradocs` profile (`SPRING_PROFILES_ACTIVE=auradocs`). The profile sets:

| Property | Value | Env override |
|---|---|---|
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | `http://localhost:8080/realms/auraDOCS` | `WMS_OIDC_ISSUER` |
| `wms.identity.keycloak.base-url` | `http://localhost:8080` | `WMS_KEYCLOAK_URL` |
| `wms.identity.keycloak.realm` | `auraDOCS` | `WMS_KEYCLOAK_REALM` |
| `wms.identity.source` | `AURADOCS` | `WMS_IDENTITY_SOURCE` |
| `wms.identity.auradocs.base-url` | `http://localhost:9191` | `WMS_AURADOCS_URL` |
| `wms.identity.auradocs.directory-path` | `/integration/wms/directory` | |
| `wms.identity.auradocs.role-map` | `ROLE_ADMIN: wms-admin` | |
| `wms.identity.org-source.manager` | `LOCAL` | |

`WMS_IDENTITY_SYNC_SECRET` must be the secret of the `wms-identity-sync` client in the auraDOCS realm.

What the sync does with `source=AURADOCS`:

- Users and their realm roles come from Keycloak, as with `KEYCLOAK`.
- The sync client's token is used to call `GET {base-url}{directory-path}` on auraDOCS. auraDOCS users are matched to
  Keycloak users by lower-cased email. Inactive auraDOCS users and users with no matching email get nothing from
  auraDOCS.
- Each active auraDOCS group becomes group `adg-<id>` with path `/<name>`, and each department becomes `add-<id>` with
  path `/dept/<name>`. Ids longer than 36 characters are replaced by a stable hash. Inactive groups are skipped.
  Assign process steps with `${group:/Finance}` or `${group:/dept/Finance}`.
- A matched user's `department` is the name of their first auraDOCS department.
- The role map adds WMS roles on top of the Keycloak ones. It maps an auraDOCS role to one or more WMS roles
  (comma-separated). Keep the brackets around keys, e.g. `"[ROLE_ADMIN]": wms-admin`, so that Spring does not change
  the key.
- Managers are edited in WMS because auraDOCS has no reporting lines.

## Other services and the gateway

Start the gateway, workflow, form, document, notification and audit services with the `auradocs` profile too
(the IntelliJ run configurations in `.idea/runConfigurations` already do). The profile sets the issuer to
`http://localhost:8080/realms/auraDOCS` and the service-account token URLs that derive from it, and for
notification-service sets `wms.notification.app-url` to `http://localhost:4300`.

- `WMS_OIDC_ISSUER` and `WMS_APP_URL` still override these values.
- Use the auraDOCS-realm secrets of the service clients: `WMS_WORKFLOW_CLIENT_SECRET`, `WMS_FORM_CLIENT_SECRET`,
  `WMS_DOCUMENT_CLIENT_SECRET` and `WMS_NOTIFICATION_CLIENT_SECRET`.
- Gateway CORS for `/api/**`: `WMS_CORS_ALLOWED_ORIGINS` is a comma-separated list and must include the auraDOCS
  front's origin, because its document panel calls the workflow API from the browser. The default is
  `http://localhost:4200`. Add `http://localhost:4300` only if the WMS front calls the gateway directly rather than
  through its dev proxy.

## Workflow API used by auraDOCS

- `POST /api/workflow/instances` with `businessKey: "auradocs-doc:<docId>"`. Data keys that start with `auradocs`
  (`auradocsDocId`, `auradocsTemplateId`, `auradocsDocName`) are kept even when the start form does not declare them.
  Like all start data, they are stored under `variables.form`, e.g. `variables.form.auradocsDocId`.
- `GET /api/workflow/instances?businessKey=auradocs-doc:<docId>` lists the requests under that key that the caller may
  see: requests they started or take part in, or all of them for admins. Without the parameter it still lists the
  caller's own requests.
