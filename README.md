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
kept - for single-leg transfers reconciliation via /transfers/status is still a
documented TODO; two-leg transfers have their own reconcile endpoint, see P6 below), and
success writes legacy `BASE_FT` plus `CORE_JOURNAL`/`TRX_REF_NO`/`EXECUTED_DT` on the
task (V3).

Execution mode is `app.execution.mode` (`EXECUTION_MODE`, default `sync`). In `sync`
the transfer executes inside the releasing request, so the approve/submit answer carries
the verdict (EXECUTED / FAILED / UNKNOWN). In `kafka` the workflow transaction lands the
task `QUEUED` together with a row in the transactional outbox `TRX_EVENT_OUTBOX` (V4);
`ExecutionOutboxRelay` drains that table on a timer (`outbox-poll-ms`, `outbox-batch`,
`outbox-max-attempts` - a row that exhausts its attempts flips FAILED and is logged at
ERROR while the task stays QUEUED) and publishes to `direct.transfer.execution.requested`
(key = task id, so one task's events stay ordered); `ExecutionEventConsumer` claims the
task `QUEUED -> EXECUTING` under the same VERSION guard and runs the identical execution
path, then the verdict is published on `direct.transfer.execution.completed`; listener
infrastructure failures dead-letter to `<topic>.DLT`. The release/submit answer is then
`QUEUED` and the FE polls the task until a final status. Nothing under `spring.kafka`
connects in `sync` mode. Charge computation (BASE_FT's `CH_TYP_1_*` columns) is a
separate feature, not built here.

Conventions follow direct-account (pom, MyBatis, Flyway with an own history table on the
shared DIRECT schema, test setup) and direct-admin (mapper style against legacy tables,
service/controller layering, UMAS client style).

## Endpoints

All under `/api/v1/companies/{companyId}` (gateway route `/api-transaction` → :5244):

| Method | Path | Purpose |
|---|---|---|
| POST | `/transfers/inquiry` | Beneficiary name via direct-integration |
| POST | `/transfers/otp/challenge` | OTP challenge via the UMAS authenticator |
| GET  | `/transfers/banks?method=LLG\|RTGS` | Destination-bank picker (P1, from legacy `COM_MT_DOM_BANK`) |
| POST | `/transfers` | Maker submit (201, or 422 with a machine code) |
| GET  | `/transfers/{taskId}?userId=` | Task detail with stages |
| GET  | `/tasks?userId=&status=PENDING` | Approval inbox (candidate on the active stage, not yet acted) |
| GET  | `/tasks/{taskId}?userId=` | Task detail (same shape as `/transfers/{taskId}`) |
| POST | `/tasks/{taskId}/approve` | Approve the active APPROVAL stage, or release on RELEASE |
| POST | `/tasks/{taskId}/reject` | Reject the task (note required) |

422 codes (fixed FE contract) - submit: `NOT_MAKER`, `SOURCE_ACCT_FORBIDDEN`,
`BANK_LIMIT`, `COMPANY_LIMIT`, `GROUP_LIMIT`, `ACCOUNT_LIMIT`, `MAKER_SCHEME_LIMIT`,
`OTP_INVALID`, `NO_MATRIX`, `NO_ELIGIBLE_APPROVER`, `NO_ELIGIBLE_RELEASER`, and (P1,
domestic types only) `TRANSFER_FIELDS_INVALID`, `BENEFICIARY_BANK_INVALID`;
approve/reject: `TASK_NOT_ACTIONABLE`, `NOT_ELIGIBLE`, `ALREADY_ACTED`, `OTP_INVALID`.

P1 (Transfer ke Bank Lain, menu `MNU_GCME_050300`): submit takes `transferType`
(`BNI` default / `LLG` / `RTGS`). Domestic submits add a beneficiary block
(`beneficiaryBankId` from the `/transfers/banks` picker, addresses, phone, postal code -
mandatory for RTGS - id type/number, `beneficiaryType` for LLG, residency codes in each
method's own vocabulary: kliring 1=Penduduk, RTGS 0=Resident). The server derives the
routing codes from the chosen `COM_MT_DOM_BANK` row (7-digit sandi + 8-char BIC for LLG,
BIC for RTGS), attaches the flat per-method fee (`app.transfer.llg.fee`/`rtgs.fee` -
P4 replaces this with the real charge engine), and the whole validation ladder plus the
usage increments run on the DEBITED total `amount + fee` (legacy's nominalPenarikan).
Execution routes by service code (`GCM_FTR_DOM_LLG`/`GCM_FTR_DOM_RTGS`) to
direct-integration's `/transfers/kliring` and `/transfers/rtgs` with the same
never-retried / UNKNOWN-on-timeout semantics; the BASE_FT row adds the beneficiary-bank
columns (BEN_DOM_BNK_ID, BEN_ADDR_1..3, LLD residency flags, BEN_TYPE, BIC_SWIFT_CD).
Workflow (candidates, OTP, approve/release, VERSION claims) is byte-for-byte the P0 path.

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

## P3 - Transfer ke Virtual Account

A VA payment is a single-leg IDR transfer against the SOA Virtual Account REST service,
executed through direct-integration like every other core call. It is **not** a
CoreServiceJSON operation: the upstream is two Spring endpoints on the UAT SOA host,
`billing-inquiry` and `billing-payment`, and the payment is stateful against the inquiry
through `inquiryRequestId`.

Flow:

1. **Inquiry** - `POST /api/v1/companies/{companyId}/transfers/va/inquiry` with
   `{userId, sourceAccountNo, vaNumber}`. The source account is part of the upstream request,
   so the FE asks for it before the "Periksa" step. The answer carries `name`, `amount`
   (may be `0` or null - the amount stays user-editable and is only pre-filled when `> 0`),
   `currency`, `inquiryRequestId`, `fee` and the upstream `responseCode`/`responseMessage`.
   An upstream refusal is a 422 `VA_INQUIRY_REJECTED` carrying the VA service's own message
   (`983 Client Tidak Ditemukan.`, `982 Gagal Mendapatkan Respons.`); an integration outage
   is a 503, never a fabricated bill.
2. **Submit** - the ordinary `POST /transfers` with `transferType: "VA"`,
   `beneficiaryAccountNo` = the VA number, `beneficiaryName` = the inquiry name (optional),
   `amount` in IDR only, and `inquiryRequestId` from step 1, which is stored on the task
   (`TRX_TASK.VA_INQUIRY_REQ_ID`, migration V9). Limits, approval matrix, OTP and the
   maker/approver/releaser stages are identical to LLG, keyed on service code
   `GCM_VA_BILLING` ("VA Billing Single") and the Transfer ke VA menu code. The flat fee
   `TRANSFER_VA_FEE` (`app.transfer.va.fee`, default 0 until the P4 fee engine) rides
   through every limit rung and usage increment like the LLG fee.
3. **Execution** - one `billing-payment` call, never retried. **Success if and only if the
   answer carries a non-empty `journalNum`**; `responseCode` alone is never trusted. The
   journal lands in `TRX_TASK.CORE_JOURNAL`, `TRX_REF_NO` is minted from the shared legacy
   counter, and the executed record is written to the legacy VA booking table
   **`VIRTUAL_ACCOUNT_FT`** (not `BASE_FT`): `VA_NO`, `VA_NAME`, `BILLED_AMOUNT`,
   `FEE_AMOUNT`, `TOTAL_AMOUNT`, `DEBITED_ACC_NO`, `REF_NO`, `TRX_REF_NO`, `CORP_ID`. A
   definite refusal (JSON error body, or a 2xx with a null `journalNum`) is `FAILED` with
   the VA message; a timeout is `UNKNOWN` with usage kept and no retry - the same
   never-a-second-debit rule as every other transfer.

Configuration:

| Where | Key | Default | Meaning |
|---|---|---|---|
| direct-integration | `VA_ENABLED` | `true` | switch the VA client on/off |
| direct-integration | `VA_INQUIRY_URL` | `http://192.168.151.220:45353/va/v3/billing-inquiry` | UAT SOA billing inquiry |
| direct-integration | `VA_PAYMENT_URL` | `http://192.168.151.220:45354/va/v3/billing-payment` | UAT SOA billing payment |
| direct-integration | `VA_CHANNEL` | `BNIDIRECT` | case-sensitive whitelist (`BNIDIRECT`, `NEWMOBILE`, `NEWIBANK` pass); which one is registered for BNI Direct is still to be confirmed with SOA |
| direct-transaction | `TRANSFER_VA_FEE` | `0` | flat fee placeholder until P4 |

Caveat - **the success shape of the inquiry has never been observed.** Every VA number
available on DEV/UAT answers `982` (client known, partner billing backend silent) or `983`
(client prefix unknown), so the field names for `inquiryRequestId`, the holder's name and
the bill amount are best-guess aliases in the integration parser. The first successful
inquiry on a registered VA fixes them; capture it with
`infra/direct-infra/scripts/p3_va_probe.py --va <VA> --raw` and update the spec md. The E2E
drill is `infra/direct-infra/scripts/p3_va_e2e.md`.

## P6 - cross-currency Transfer ke Bank Lain (two legs through a simsem account)

The Kliring and RTGS wires are IDR-only, so a Transfer ke Bank Lain debited from a valas
account cannot go straight out. It executes in two legs through a **simsem** account
("simpanan sementara": an ordinary BNI IDR deposit account used as a temporary holding
account):

1. **Leg 1** - `DepTransferCross`, customer valas account -> simsem IDR account, with
   the submit-time frozen `DEBIT_AMT`/`DEBIT_CCY_CD`/`BASE_AMT`/`RATE_TYPE` (P5 block).
2. **Leg 2** - `KliringOutwardv2` / `RtgsOutward`, simsem IDR account -> destination
   bank, identical to the single-leg P1 call except that the remitter is the simsem
   account. The `BASE_FT` row of an executed two-leg transfer carries `ACCT_NO_SIMSEM`
   and `JOURNAL_NO_SIMSEM` (leg 1's journal) next to the usual columns.

Submit-side: for `transferType` LLG/RTGS the credit currency stays IDR and the SOURCE
account may be valas; the same P5 multi-currency resolution applies (`debitAmount`
mandatory, `rateType`, limits and matrix in the debit currency). ONLINE stays IDR-only.

**Simsem pool (legacy `SIMSEM_ACCOUNT`, read-only).** Legacy ran the whole country
through ONE simsem account; the rewrite rotates across several. `SimsemPool.select(srvcCd,
ccy)` reads the rows registered for the service code and currency and picks the account
with the fewest tasks mid-two-leg (`TWO_LEG_STATE = 'LEG1_DONE'`), tie-break on the lowest
`ACCOUNT_NO`. An empty pool fails the transfer with a clear message - money is never
routed to an unregistered holding account. The table has no active flag: the pool is
managed by inserting/removing rows.

**Seeding rule.** Simsem account numbers are environment/ops DATA, never a Flyway
migration or a seed file in this repo. On DEV, ordinary IDR accounts are registered as
dummies with `infra/direct-infra/scripts/p6_seed_simsem.py` (`CREATED_BY 'P6SEED'`);
production swaps in the real numbers as a data change. `p6_verify.py` in the same folder
shows the pool, whether V8 has run, and the in-flight count per account.

**Two-leg state on `TRX_TASK` (V8, own table):** `SIMSEM_ACCT_NO` (the account chosen
for this task), `JOURNAL_NO_SIMSEM` (leg 1's journal; leg 2's rides on `CORE_JOURNAL`),
and `TWO_LEG_STATE`:

| `TWO_LEG_STATE` | Task `STATUS` | Meaning |
|---|---|---|
| `NONE` / NULL | any | not a two-leg task, or leg 1 not confirmed (a leg-1 timeout is UNKNOWN with no state - reconcile it like a single-leg UNKNOWN) |
| `LEG1_DONE` | EXECUTING / UNKNOWN | money is in the simsem account; leg 2 running, or timed out (UNKNOWN). Counted as in-flight by the pool. **Never refunded blindly.** |
| `LEG2_DONE` | EXECUTED | outward from simsem confirmed, both journals persisted |
| `REFUND_DONE` | FAILED | leg 2 definitely refused; the reverse `DepTransferCross` simsem -> customer confirmed |
| `REFUND_FAILED` | UNKNOWN | leg 2 refused AND the refund could not be confirmed - funds may be stranded in `SIMSEM_ACCT_NO`; needs an operator |

`markLeg1Done` is committed in its own transaction so the in-flight count and the
reconciliation see it even if the request thread dies between the legs.

**Reconciliation (task 6.5).** A task left `UNKNOWN` + `LEG1_DONE` is settled through
the reconcile endpoint (`POST /api/v1/companies/{companyId}/tasks/{taskId}/reconcile`, body `{"userId"}`, header `X-User-Id`, permission `task-pending`; 422 `TASK_NOT_RECONCILABLE` unless the task is UNKNOWN + LEG1_DONE; outcome EXECUTED | REFUNDED | REFUND_FAILED | INCONCLUSIVE | NOOP
- confirm the final path in `TaskController`), which asks direct-integration whether the
outward from the simsem account landed and then either finalizes `EXECUTED`/`LEG2_DONE`
(plus the `BASE_FT` row) or runs the refund and lands `FAILED`/`REFUND_DONE`. Both
updates are guarded on the exact pre-state, so a repeated call is a no-op. There is no
scheduled job; the drill for all three outcomes is
`infra/direct-infra/scripts/p6_e2e_drill.md`.
