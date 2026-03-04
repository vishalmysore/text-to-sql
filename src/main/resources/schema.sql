-- ============================================================
-- "Failure-by-Design" Enterprise Schema v2 — 11 Tables
-- Spans: CRM, Finance, Products, Compliance, Risk
-- Every naming convention is deliberately inconsistent across
-- domains, forcing an LLM to hallucinate joins and semantics.
-- ============================================================

-- ── DOMAIN 1: CRM ─────────────────────────────────────────────
-- C_STS_CD: 0=Pending, 1=Active, 9=Deleted
-- C_SEG: 'P'=Private, 'C'=Corporate, 'I'=Institutional
CREATE TABLE CLIENTS (
    C_ID       INTEGER PRIMARY KEY AUTO_INCREMENT,
    C_NAME     VARCHAR(100) NOT NULL,
    C_STS_CD   INTEGER NOT NULL DEFAULT 0,
    C_SEG      VARCHAR(10),
    C_ONBD_DT  DATE,
    C_MGR_REF  INTEGER           -- references STAFF.S_ID (no FK declared)
);

-- CONTACT_INFO stores communication details for clients.
-- Different dept uses X_ prefix instead of C_.
CREATE TABLE CONTACT_INFO (
    X_ID       INTEGER PRIMARY KEY AUTO_INCREMENT,
    X_ENT_ID   INTEGER NOT NULL,  -- foreign key to CLIENTS.C_ID (name gives NO hint)
    X_ENT_TYPE CHAR(1),           -- 'C'=Client, 'S'=Staff (polymorphic, undocumented)
    X_CHANNEL  CHAR(1),           -- 'E'=Email, 'P'=Phone, 'M'=Mail
    X_VAL      VARCHAR(200),      -- the actual email/phone/address value
    X_PREF     INTEGER DEFAULT 0  -- 1=primary contact, 0=secondary (no descriptive name)
);

-- ── DOMAIN 2: STAFF & ORG ──────────────────────────────────────
-- S_LVL: 1=Analyst, 2=Associate, 3=VP, 4=MD — no labels in schema
CREATE TABLE STAFF (
    S_ID       INTEGER PRIMARY KEY AUTO_INCREMENT,
    S_NM       VARCHAR(100),
    S_LVL      INTEGER,           -- seniority level (opaque integer)
    S_DEPT_CD  VARCHAR(5),        -- 'OPS','RISK','CMPL','FIN' — not readable
    S_MGR      INTEGER            -- self-referencing; no FK constraint
);

-- ── DOMAIN 3: PRODUCTS ────────────────────────────────────────
-- PRD_CAT: 'EQ'=Equity, 'FI'=Fixed Income, 'DRV'=Derivative
-- PRD_RK: risk rank 1-5 (no labels)
CREATE TABLE PRODUCTS (
    P_ID       INTEGER PRIMARY KEY AUTO_INCREMENT,
    P_CODE     VARCHAR(20) UNIQUE,
    PRD_CAT    VARCHAR(5),
    PRD_DESC   VARCHAR(500),
    PRD_RK     INTEGER,           -- risk rank 1 (low) to 5 (high) — LLM cannot know scale
    PRD_FEE_BP DECIMAL(8,4)       -- fee in basis points — unit not in column name
);

-- CLIENT_PRODUCT is the many-to-many bridge between CLIENTS and PRODUCTS.
-- Named differently from both parent tables (no C_ or P_ prefix clue).
CREATE TABLE CLIENT_PRODUCT (
    CP_ID      INTEGER PRIMARY KEY AUTO_INCREMENT,
    CP_CLT_ID  INTEGER NOT NULL,  -- → CLIENTS.C_ID (no FK)
    CP_PRD_ID  INTEGER NOT NULL,  -- → PRODUCTS.P_ID (no FK)
    CP_ALLOC   DECIMAL(5,2),      -- allocation % — column name gives no hint it's a %
    CP_DT_FROM DATE,
    CP_DT_TO   DATE               -- NULL means still active
);

-- ── DOMAIN 4: TRANSACTIONS ────────────────────────────────────
-- T_TYPE: 'P'=Payment, 'R'=Refund, 'C'=Charge, 'S'=Settlement
-- C_REF is NOT a declared FK even though it maps to CLIENTS.C_ID
CREATE TABLE TXN_LOG (
    T_ID       INTEGER PRIMARY KEY AUTO_INCREMENT,
    C_REF      INTEGER NOT NULL,
    P_REF      INTEGER,           -- → PRODUCTS.P_ID (optional, no FK)
    T_AMT      DECIMAL(15, 2),
    T_TYPE     CHAR(1),
    T_STS      CHAR(1),           -- 'A'=Approved, 'P'=Pending, 'R'=Rejected, 'C'=Cancelled
    T_TS       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    T_CCY      CHAR(3) DEFAULT 'USD',
    T_DESK_CD  VARCHAR(5)         -- 'NY','LON','HK','SG' — booking desk
);

-- TXN_AUDIT stores change events for each transaction (separate audit domain).
-- Uses TA_ prefix and references T_ID by a different name.
CREATE TABLE TXN_AUDIT (
    TA_ID      INTEGER PRIMARY KEY AUTO_INCREMENT,
    TA_TXN_REF INTEGER NOT NULL,  -- → TXN_LOG.T_ID (no FK)
    TA_ACT     CHAR(1),           -- 'C'=Created,'M'=Modified,'V'=Voided
    TA_BY      INTEGER,           -- → STAFF.S_ID
    TA_TS      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    TA_NOTE    VARCHAR(500)
);

-- ── DOMAIN 5: FINANCE / METRICS ───────────────────────────────
-- VAL_A=gross_revenue, VAL_B=net_revenue, VAL_C=costs
-- M_ENT_ID could be a client OR a product (polymorphic)
CREATE TABLE FIN_METRICS (
    M_ID       INTEGER PRIMARY KEY AUTO_INCREMENT,
    M_ENT_ID   INTEGER,
    M_ENT_TYPE CHAR(1),           -- 'C'=Client, 'P'=Product (same code 'C' as CLIENT SEGMENT above!)
    M_PRD      VARCHAR(6),        -- period 'YYYYMM' — a VARCHAR, not a DATE
    VAL_A      DECIMAL(15, 2),
    VAL_B      DECIMAL(15, 2),
    VAL_C      DECIMAL(15, 2),
    M_DATE     DATE
);

-- BUDGET stores planned vs actual on a per-department, per-quarter basis.
-- Uses completely different column naming from FIN_METRICS.
CREATE TABLE BUDGET (
    B_ID       INTEGER PRIMARY KEY AUTO_INCREMENT,
    B_DEPT     VARCHAR(5),        -- same dept codes as STAFF.S_DEPT_CD (no FK)
    B_QTR      CHAR(6),           -- 'YYYYQN' e.g. '2024Q1' — different format from M_PRD!
    PLANNED    DECIMAL(15,2),
    ACTUAL     DECIMAL(15,2),
    DELTA      DECIMAL(15,2)      -- could be PLANNED-ACTUAL or ACTUAL-PLANNED — ambiguous sign
);

-- ── DOMAIN 6: COMPLIANCE ──────────────────────────────────────
-- KYC_REC covers Know-Your-Customer records.
-- KYC_LVL: 1=Basic, 2=Enhanced, 3=PEP (Politically Exposed Person)
CREATE TABLE KYC_REC (
    K_ID       INTEGER PRIMARY KEY AUTO_INCREMENT,
    K_CLT_ID   INTEGER NOT NULL,  -- → CLIENTS.C_ID (no FK)
    KYC_LVL    INTEGER,           -- 1=Basic, 2=Enhanced, 3=PEP — LLM must know industry jargon
    KYC_STS    CHAR(1),           -- 'P'=Pass, 'F'=Fail, 'R'=Review (same 'R' as TXN_LOG.T_STS=Rejected!)
    KYC_EXP_DT DATE,              -- expiry date — expired KYC means blocked trading
    K_RVW_BY   INTEGER            -- → STAFF.S_ID
);

-- ALERT_LOG captures compliance and risk alerts.
-- A_SVRTY: 0=Info, 1=Low, 2=Medium, 3=High, 4=Critical
CREATE TABLE ALERT_LOG (
    A_ID       INTEGER PRIMARY KEY AUTO_INCREMENT,
    A_ENT_ID   INTEGER,           -- could be C_ID, T_ID, or K_ID (polymorphic again!)
    A_ENT_TYPE CHAR(1),           -- 'C'=Client, 'T'=Transaction, 'K'=KYC
    A_SVRTY    INTEGER,
    A_CAT      VARCHAR(10),       -- 'AML','FRAUD','LIMIT','BREACH' — no full names
    A_STS      CHAR(1),           -- 'O'=Open, 'C'=Closed (same letter as 'C'=Corporate in C_SEG!)
    A_TS       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    A_OWN_ID   INTEGER            -- → STAFF.S_ID (alert owner)
);

-- ============================================================
-- Seed Data
-- ============================================================

INSERT INTO CLIENTS (C_NAME, C_STS_CD, C_SEG, C_ONBD_DT, C_MGR_REF) VALUES
    ('John Doe',        1, 'P', '2023-01-15', 2),
    ('Acme Corp',       1, 'C', '2022-06-01', 3),
    ('Jane Smith',      0, 'P', '2024-03-10', 2),  -- Pending
    ('Global Ventures', 9, 'I', '2021-11-20', 4),  -- Deleted
    ('Bob Johnson',     1, 'C', '2023-07-22', 3),
    ('MegaFund LLC',    1, 'I', '2020-04-30', 4),
    ('FinTech Alpha',   1, 'P', '2024-01-05', 2),
    ('RegCo Partners',  0, 'C', '2023-11-15', 3);

INSERT INTO STAFF (S_NM, S_LVL, S_DEPT_CD, S_MGR) VALUES
    ('Alice Hart',   4, 'FIN',  NULL),
    ('Bob Chen',     3, 'RISK', 1),
    ('Carol Davis',  3, 'CMPL', 1),
    ('Dan Patel',    4, 'OPS',  1),
    ('Eva Kim',      2, 'FIN',  2),
    ('Frank Osei',   1, 'RISK', 2),
    ('Grace Sun',    2, 'CMPL', 3);

INSERT INTO CONTACT_INFO (X_ENT_ID, X_ENT_TYPE, X_CHANNEL, X_VAL, X_PREF) VALUES
    (1, 'C', 'E', 'john.doe@example.com', 1),
    (1, 'C', 'P', '+1-555-0101', 0),
    (2, 'C', 'E', 'finance@acmecorp.com', 1),
    (5, 'C', 'E', 'bob.j@example.com', 1),
    (6, 'C', 'E', 'info@megafund.com', 1);

INSERT INTO PRODUCTS (P_CODE, PRD_CAT, PRD_DESC, PRD_RK, PRD_FEE_BP) VALUES
    ('EQ-US-001',  'EQ',  'US Large Cap Equity Fund',          2,  75.0),
    ('FI-GOV-001', 'FI',  'US Treasury Bond Portfolio',         1,  25.0),
    ('DRV-OPT-001','DRV', 'Equity Options Strategy',            5, 150.0),
    ('EQ-EM-001',  'EQ',  'Emerging Markets Equity Fund',       4, 100.0),
    ('FI-CRD-001', 'FI',  'Investment Grade Credit Portfolio',  2,  50.0);

INSERT INTO CLIENT_PRODUCT (CP_CLT_ID, CP_PRD_ID, CP_ALLOC, CP_DT_FROM, CP_DT_TO) VALUES
    (1, 1, 60.00, '2023-02-01', NULL),
    (1, 2, 40.00, '2023-02-01', NULL),
    (2, 3, 30.00, '2022-07-01', NULL),
    (2, 4, 70.00, '2022-07-01', '2024-01-01'), -- ended
    (5, 1, 50.00, '2023-08-01', NULL),
    (6, 3, 100.0, '2020-05-01', NULL);

INSERT INTO TXN_LOG (C_REF, P_REF, T_AMT, T_TYPE, T_STS, T_CCY, T_DESK_CD) VALUES
    (1, 1,    1500.00, 'P', 'A', 'USD', 'NY'),
    (1, 2,     200.00, 'R', 'A', 'USD', 'NY'),
    (2, 3,  750000.00, 'P', 'A', 'EUR', 'LON'),
    (2, 3,   15000.00, 'C', 'A', 'EUR', 'LON'),
    (5, 1,   42000.00, 'P', 'A', 'USD', 'NY'),
    (5, 1,    3000.00, 'P', 'P', 'USD', 'NY'),  -- Pending
    (6, 3,  999999.00, 'P', 'A', 'USD', 'HK'),
    (3, NULL,   999.00,'P', 'A', 'USD', 'NY'),  -- Pending client!
    (7, 4,   50000.00, 'P', 'R', 'USD', 'SG'),  -- Rejected
    (2, 5,    8000.00, 'S', 'A', 'EUR', 'LON');

INSERT INTO TXN_AUDIT (TA_TXN_REF, TA_ACT, TA_BY, TA_NOTE) VALUES
    (1, 'C', 5, 'Initial entry'),
    (3, 'C', 5, 'Large transaction flagged'),
    (3, 'M', 3, 'Compliance reviewed'),
    (7, 'C', 6, 'Auto-generated'),
    (7, 'M', 6, 'Risk limit breach — rejected'),
    (9, 'C', 6, 'Pending KYC clearance');

INSERT INTO FIN_METRICS (M_ENT_ID, M_ENT_TYPE, M_PRD, VAL_A, VAL_B, VAL_C, M_DATE) VALUES
    (2, 'C', '202401', 850000.00, 720000.00,  95000.00, '2024-01-31'),
    (5, 'C', '202401',  45000.00,  38000.00,   5000.00, '2024-01-31'),
    (6, 'C', '202401', 999999.00, 950000.00, 100000.00, '2024-01-31'),
    (1, 'C', '202401',   1500.00,   1300.00,    100.00, '2024-01-31'),
    (1, 'P', '202401',  32000.00,  28000.00,   3000.00, '2024-01-31'), -- Product metrics
    (3, 'P', '202401', 875000.00, 800000.00,  50000.00, '2024-01-31');

INSERT INTO BUDGET (B_DEPT, B_QTR, PLANNED, ACTUAL, DELTA) VALUES
    ('FIN',  '2024Q1', 1000000.00, 1050000.00,  50000.00),
    ('RISK', '2024Q1',  200000.00,  185000.00, -15000.00),
    ('CMPL', '2024Q1',  150000.00,  170000.00,  20000.00),
    ('OPS',  '2024Q1',  500000.00,  480000.00, -20000.00),
    ('FIN',  '2024Q2', 1100000.00,  950000.00, -150000.00);

INSERT INTO KYC_REC (K_CLT_ID, KYC_LVL, KYC_STS, KYC_EXP_DT, K_RVW_BY) VALUES
    (1, 1, 'P', '2025-01-15', 7),
    (2, 2, 'P', '2024-12-01', 3),
    (3, 1, 'R', '2024-06-10', 7),  -- Under review
    (5, 1, 'P', '2025-07-22', 7),
    (6, 3, 'P', '2025-04-30', 3),  -- PEP client!
    (7, 2, 'F', '2024-02-05', 3),  -- FAILED KYC
    (8, 1, 'R', '2025-11-15', 7);

INSERT INTO ALERT_LOG (A_ENT_ID, A_ENT_TYPE, A_SVRTY, A_CAT, A_STS, A_OWN_ID) VALUES
    (3, 'T', 4, 'FRAUD', 'O', 2),       -- Critical fraud alert on TXN 3
    (6, 'C', 3, 'AML',   'O', 3),       -- High AML alert on client 6 (MegaFund)
    (7, 'K', 2, 'BREACH','C', 7),       -- Closed KYC breach
    (9, 'T', 3, 'LIMIT', 'O', 2),       -- High limit breach on rejected TXN
    (2, 'C', 1, 'AML',   'C', 3);       -- Closed low AML

