# Schema versioning

Flyway owns this schema from the first commit. There is no hand-run DDL script and no
"which environment has which column" question: `src/main/resources/db/migration` is the
schema, and `flyway_schema_history` in each database records how far that database has got.

This is the difference from spine-backoffice, whose tables predated its repository and had
to be dumped out of Oracle into a baseline before Flyway could track them.

## Adding a change

New file, never an edit to an applied one:

```
src/main/resources/db/migration/V2__add_account_currency.sql
```

Versions must increase. The migration applies on next boot in every environment, in order,
exactly once. Flyway records each file's checksum, so editing `V1` after it has run fails
`validate` at boot on every environment that already ran it — which is the point. Correct a
mistake with `V3`, not by rewriting `V2`; Flyway Community has no undo.

Keep environment-specific things out of the SQL: no tablespace or storage clauses, no
schema prefixes on object names. A migration that names `SPINE.ACCOUNT` cannot run in a
schema called anything else.

## Who runs it

By default the application runs migrations at boot as the `spring.datasource` user, which
means that user needs DDL rights.

Where the bank forbids that — the usual case for production — split it:

- Give the pod a DML-only Oracle user and set `FLYWAY_ENABLED=false`.
- Run `flyway migrate` from the deployment pipeline with the schema-owner credential, or
  set `SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD` on a one-shot job.

The migration files and the history table are identical either way.

## Reference data

`V1` seeds `REF_ACCOUNT_STATUS`. Reference rows that the code depends on belong in
migrations, because a code path that assumes `ACTIVE` exists is broken in any environment
where nobody remembered to insert it. Business data does not belong here.
