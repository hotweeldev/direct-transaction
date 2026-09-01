-- The frozen candidate set of the approve/release phase.
--
-- REQUIREMENT recorded here so nobody "optimizes" it into a live query later: the set of
-- users eligible to act on a stage is FROZEN at task creation. Candidates are materialized
-- from the legacy user/role tables at maker submit, and the inbox and the eligibility
-- checks read ONLY this table. A user onboarded (or re-roled) after a task was submitted
-- must NOT see older tasks even when they match the stage's criteria - and a user whose
-- role is later revoked keeps visibility of tasks minted while they held it, which the
-- business accepts as the price of a stable inbox.
--
-- USER_ID is CORP_USR.USER_ID (the login id the inbox filters by), CORP_USR_ID the stable
-- CORP_USR.ID surrogate the action rows store - both are kept because the login id can be
-- renamed while a task is in flight.
--
-- V2 also widens TRX_TASK_STAGE.STATUS semantics: a stage terminated by a REJECT is set to
-- 'CLOSED' (alongside V1's WAITING / ACTIVE / DONE), so a closed stage is never confused
-- with one that was actually completed.

CREATE TABLE TRX_TASK_CANDIDATE (
    ID              NVARCHAR2(40)  NOT NULL,
    TRX_TASK_ID     NVARCHAR2(40)  NOT NULL,
    STAGE_SEQ       NUMBER(5)      NOT NULL,
    -- CORP_USR.USER_ID, the login id.
    USER_ID         NVARCHAR2(40)  NOT NULL,
    -- CORP_USR.ID, the surrogate TRX_TASK_ACTION.ACTOR_USER_ID stores.
    CORP_USR_ID     NVARCHAR2(40)  NOT NULL,
    USER_NAME       NVARCHAR2(200),
    CORP_USR_GRP_ID NVARCHAR2(40),
    CREATED_BY      NVARCHAR2(100),
    CREATED_DT      DATE           DEFAULT SYSDATE NOT NULL,
    CONSTRAINT PK_TRX_TASK_CANDIDATE PRIMARY KEY (ID),
    CONSTRAINT FK_TRX_TASK_CAND_TASK FOREIGN KEY (TRX_TASK_ID) REFERENCES TRX_TASK (ID),
    CONSTRAINT UK_TRX_TASK_CAND UNIQUE (TRX_TASK_ID, STAGE_SEQ, USER_ID)
);

-- The inbox enters by login id; the unique constraint above already covers the
-- task-side lookups.
CREATE INDEX IX_TRX_TASK_CAND_USER ON TRX_TASK_CANDIDATE (USER_ID);
