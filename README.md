# direct-transaction

Corporate transaction workflow engine for BNI Direct. Phase one covers the MAKER flow for
Transfer ke BNI (menu `MNU_GCME_050200`): beneficiary inquiry, OTP challenge, submit with
the full validation ladder, and the task-status read. Phase two adds the APPROVAL flow:
candidate materialization at submit (the eligible-user set is frozen per task, V2's
`TRX_TASK_CANDIDATE`), the candidate inbox, approve/release and reject with the same OTP
step, and an optimistic-VERSION claim that makes two simultaneous approvals count once.
Phase three is EXECUTION, behind the `ExecutionService` seam: a task whose RELEASE stage
completes (or a single-user submit) is claimed EXECUTING under the same VERSION
discipline, the two usage-tracked limits are re-validated FOR UPDATE and incremented
(usage moves at release - the recorded decision), the transfer goes to core banking
through direct-integration (never retried; a timeout lands the task UNKNOWN with usage
kept - reconciliation via /transfers/status is a documented TODO), and success writes
legacy `BASE_FT` plus `CORE_JOURNAL`/`TRX_REF_NO`/`EXECUTED_DT` on the task (V3).
Execution runs synchronously in the releasing request, so the approve/submit answer
carries the verdict (EXECUTED / FAILED / UNKNOWN); async execution is future work.
Charge computation (BASE_FT's `CH_TYP_1_*` columns) is a separate feature, not built
here.

Conventions follow direct-account (pom, MyBatis, Flyway with an own history table on the
shared DIRECT schema, test setup) and direct-admin (mapper style against legacy tables,
service/controller layering, UMAS client style).

## Endpoints

All under `/api/v1/companies/{companyId}` (gateway route `/api-transaction` → :5244):

| Method | Path | Purpose |
|---|---|---|
| POST | `/transfers/inquiry` | Beneficiary name via direct-integration |
| POST | `/transfers/otp/challenge` | OTP challenge via the UMAS authenticator |
| POST | `/transfers` | Maker submit (201, or 422 with a machine code) |
| GET  | `/transfers/{taskId}?userId=` | Task detail with stages |
| GET  | `/tasks?userId=&status=PENDING` | Approval inbox (candidate on the active stage, not yet acted) |
| GET  | `/tasks/{taskId}?userId=` | Task detail (same shape as `/transfers/{taskId}`) |
| POST | `/tasks/{taskId}/approve` | Approve the active APPROVAL stage, or release on RELEASE |
| POST | `/tasks/{taskId}/reject` | Reject the task (note required) |

422 codes (fixed FE contract) - submit: `NOT_MAKER`, `SOURCE_ACCT_FORBIDDEN`,
`BANK_LIMIT`, `COMPANY_LIMIT`, `GROUP_LIMIT`, `ACCOUNT_LIMIT`, `MAKER_SCHEME_LIMIT`,
`OTP_INVALID`, `NO_MATRIX`, `NO_ELIGIBLE_APPROVER`, `NO_ELIGIBLE_RELEASER`;
approve/reject: `TASK_NOT_ACTIONABLE`, `NOT_ELIGIBLE`, `ALREADY_ACTED`, `OTP_INVALID`.

## Authentication and authorization

direct-admin's UMAS security stack, copied per-repo (the house pattern - this Maven
module cannot depend on the Gradle platform):

- **`app.umas.auth.enabled`** (`UMAS_AUTH_ENABLED`, default `false`) -
  `UmasBearerAuthFilter` validates the Bearer JWT the UMAS gateway forwards: RSA
  signature against `UMAS_JWK_PUBLIC_KEY`, expiry, `POSTLOGIN` scope, and optionally the
  token-service jti presence check (`UMAS_PRESENCE_CHECK_ENABLED`, fail-closed). The
  token's `user_id` becomes the one identity downstream trusts: an `X-User-Id` header
  that contradicts it is a 403 (kept only for wire-shape compatibility), then the header
  is overwritten with the token identity. `IdentityBindingInterceptor` binds the path
  `{companyId}` to the token's `company_id` claim and any `userId` **query parameter** to
  the token identity; `TokenIdentity` (called in the controllers) does the same for the
  `userId` **body** fields on otp/challenge, submit, approve and reject. Every mismatch
  is an opaque 403 `{"message":"Akses ditolak","errorCode":"FORBIDDEN"}`.
- **`app.umas.authorization.enabled`** (`UMAS_AUTHORIZATION_ENABLED`, default `false`) -
  `PermissionEnforcementInterceptor` asks UMAS `POST /internal/authorization/v1/decisions`
  (CORPORATE_USER, tenant `bni-direct`, fail-closed per RBAC_TENANT_INTEGRATION.md §7)
  whether the caller holds the endpoint's `@RequiresPermission` resource:
  `transfer-bni:ACCESS` on `/transfers*`, `task-pending:ACCESS` on `/tasks*` - both
  seeded by direct-umas V13/V20. Requires `app.umas.auth.enabled=true`;
  `UmasEnforcementStartupCheck` refuses to boot otherwise.

With both flags off (the default) the endpoints are OPEN and identity is trusted as sent
- fine for local development, so the service must then not be reachable from outside the
cluster. Env names match direct-admin's, so one deployment value routes all services.

## Storage

- Own tables (Flyway V1+V2, history in `flyway_schema_history_transaction`): `TRX_TASK`,
  `TRX_TASK_STAGE`, `TRX_TASK_ACTION`, `TRX_TASK_CANDIDATE` (the per-task FROZEN
  eligible-user set - a user onboarded after submit must not see older tasks). Legacy
  `GCM_PENDING_TASK` is deliberately NOT used - the legacy app keeps its own inbox; this
  workflow is separate.
- Legacy tables read (never created here): `CORP_USR`, `CORP_USR_GRP`, `GCM_ST_APRV_MAP`,
  `SPI_GE_USER`, `CORP_ACCT_GRP(_DTL)`, `CORP_ACCT`, `BANK_TRX_LMT`, `CORP_LMT_PC_DTL`,
  `CORP_USR_GRP_LMT`, `AUTH_LMT_SCHEME`, `CORP`, `CORP_APRV_MTRX_MSTR/_SUB/_DTL`,
  `COM_ST_SRVC_CCY(_MTRX)`.
- Legacy tables written: `TRX_REF_NO_SEQUENCE` - the reference-number counter is SHARED
  with the parallel legacy application (SELECT ... FOR UPDATE, then increment) so the two
  never mint the same `REF_NO`; and, at execution, `CORP_LMT_PC_DTL` /
  `CORP_USR_GRP_LMT` (usage increments) and `BASE_FT` (the booking row of an executed
  transfer, minimal column set).

## Running locally

```powershell
Copy-Item .env.example .env   # then fill ORACLE_PASSWORD etc.
mvn package
.\run-local.ps1
```

`SERVER_PORT` defaults to 5244. The service connects AS the DIRECT schema user; legacy
tables are reachable by bare name.
