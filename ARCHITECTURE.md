# Backend structure

## Package by feature

Each service is split into **feature packages**, not technical layers: `lk.auradot.wms.<service>.<feature>`.
A feature package holds everything for that feature — its controller, its store, its request/response records and its
logic. That is why every service has different folders: the folders name what the service does
(`workflow/instance`, `form/submission`), not what kind of class sits inside (`controller`, `repository`).

The top-level package of a service keeps only the `*Application` class and small shared helpers (`Json`, `Times`,
`*Config`).

## Reading a package

The layers still exist; class-name suffixes tell you the role.

| Layered term | Here | Example |
|---|---|---|
| Controller | `*Controller` (`@RestController`) | [form/submission/SubmissionController](form-service/src/main/java/lk/auradot/wms/form/submission/SubmissionController.java) |
| Repository / DAO | `*Store` (`@Repository`, `JdbcClient` + plain SQL) | [form/submission/SubmissionStore](form-service/src/main/java/lk/auradot/wms/form/submission/SubmissionStore.java) |
| DTO | records named `*Request`, `*View`, `*Summary`, `*Page`, `*Response` | `StartRequest`, `TaskView`, `InstanceSummary` |
| Entity | plain records (`Submission`, `StoredDefinition`, `DocumentRow`) | — |
| Service | a class named after its job, or `*Service` when a feature needs one — never `*ServiceImpl` | `SubmissionValidator`, `CompiledForms`, `WorkflowCommands` / `WorkflowQueries`, `document/folder/FolderService` |
| Config | `*Config` (beans), `*Properties` (`@ConfigurationProperties`) | `FormsConfig`, `FormClientProperties` |

Interfaces appear only where there is a real seam — usually a call to another service, so it can be faked in tests:
`workflow/forms/FormClient` → `FormServiceClient`, `workflow/directory/Directory` → `IdentityDirectory`,
`document/storage/ObjectStore` → `S3ObjectStore`, `notification/email/Mailer` → `SmtpMailer`.

## Why

- A change to one feature usually touches one folder.
- No interface + `Impl` pair for classes that only ever have one implementation.
- Classes are named for what they do instead of growing into a generic `*ServiceImpl`.
- Internals a feature does not share (e.g. `*Scheduler` classes) stay package-private.
- Folder names alone describe the service.

## Service map

| Module | Feature packages |
|---|---|
| `common` | `error` (`ApiException`, global handler), `messaging` (outbox + RabbitMQ events), `security` (Keycloak roles, service tokens), `web` (correlation id) |
| `expression` | condition language (parser and evaluator) used by `workflow-engine` |
| `workflow-engine` | `definition` (process graph, validation, compiler), `runtime` (`ProcessEngine`, tokens, tasks, jobs, work calendar) — no Spring, no database |
| `gateway` | routing, security and error responses for the edge |
| `identity-service` | `user` (users, groups, roles), `org` (manager/department/position), `sync` (Keycloak full sync), `jit` (provision a user on first request) |
| `workflow-service` | `definition` (drafts, publish, catalog), `instance` (start, tasks, history), `job` (timers, system actions, webhooks), `forms` (client → form-service), `directory` (client → identity-service), `monitor`, `stats`, `metrics` |
| `form-service` | `definition` (drafts, publish, versions), `submission` (validate, submit), `model` (form schema and validation rules), `files` (client → document-service) |
| `document-service` | `document`, `folder`, `type` (document types and metadata), `upload`, `storage` (S3/MinIO), `access` (ACLs), `search`, `text` (text extraction), `staging` (files attached to forms), `written` (written documents and their approval), `requests` / `approval` / `forms` (clients → workflow-service, form-service), `messaging` |
| `notification-service` | `events` (listener), `plan` (who gets what), `inbox`, `email`, `push` (live WebSocket), `preference`, `directory` (client → identity-service), `text` (templates) |
| `audit-service` | `entry` (listener, store, query API) |

## Adding a feature

1. Create a package `lk.auradot.wms.<service>.<feature>`.
2. Add `<Feature>Controller`, `<Feature>Store` and the request/view records there.
3. Put logic in a class named for its job; add an interface only for calls to another service.
4. Keep classes nothing outside the package needs as package-private.
