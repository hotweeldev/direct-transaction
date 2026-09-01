-- One-time local setup: the Oracle user direct-transaction owns.
--
-- Local development only. In DEV/UAT/PROD the DBA creates the schema and the grants are
-- decided there - in particular whether the pod's runtime user gets DDL rights at all
-- (see db/README.md).
--
-- Run as SYSDBA against the local FREE instance:
--   sqlplus sys/<password>@localhost:1521/FREE as sysdba @db/create-local-user.sql

CREATE USER DIRECT IDENTIFIED BY "CHANGEME";

-- CONNECT lets it log in; RESOURCE lets it create the tables, indexes and sequences that
-- Flyway's V1 needs. Nothing wider: this user should not be able to read another schema.
GRANT CONNECT, RESOURCE TO DIRECT;

-- RESOURCE grants no storage quota on its own, so without this every CREATE TABLE fails
-- with ORA-01950: no privileges on tablespace 'USERS'.
ALTER USER DIRECT QUOTA UNLIMITED ON USERS;
