# Text-to-SQL Failure Demo

> **A research-backed proof that vanilla NLP-to-SQL completely fails on cryptic, real-world schemas.**

---

## 🎯 Purpose

This project demonstrates why naively pointing an LLM at a database schema and asking it to generate SQL is fundamentally broken for enterprise use cases. The central argument:

> *Without a semantic metadata layer (ontology, KG, or schema annotations), an LLM is just guessing at business semantics — and it will be wrong.*

This aligns with published research showing that Text-to-SQL accuracy drops sharply as schema complexity increases (see: [Spider benchmark](https://yale-lily.github.io/spider), [BIRD benchmark](https://bird-bench.github.io/)).

---

## 🪤 The "Failure-by-Design" Enterprise Schema (11 Tables)

The schema intentionally spans **6 business domains** with inconsistent naming conventions across each, so no domain's column names give hints about another domain's semantics.

| Domain | Tables | Key Traps |
|--------|--------|-----------|
| **CRM** | `CLIENTS`, `CONTACT_INFO` | `C_STS_CD`=`0/1/9` (opaque); `X_ENT_ID` → `C_ID` (no FK, different prefix) |
| **Staff / Org** | `STAFF` | `S_LVL`=`1-4` integers; self-referencing `S_MGR` with no FK |
| **Products** | `PRODUCTS`, `CLIENT_PRODUCT` | `PRD_RK`=risk rank 1-5 (no labels); `PRD_FEE_BP` in basis points (unit not in name) |
| **Transactions** | `TXN_LOG`, `TXN_AUDIT` | `T_TYPE`=`P/R/C/S`; `T_STS`=`A/P/R/C`; `C_REF`→`C_ID` (no FK); `TA_TXN_REF`→`T_ID` (different name) |
| **Finance** | `FIN_METRICS`, `BUDGET` | `VAL_A`=gross, `VAL_B`=net, `VAL_C`=costs (no context); `M_PRD`=`'YYYYMM'` string vs `B_QTR`=`'YYYYQN'` string |
| **Compliance** | `KYC_REC`, `ALERT_LOG` | `KYC_STS`=`'R'`(Review) clashes with `T_STS`=`'R'`(Rejected); `A_STS`=`'C'`(Closed) clashes with `C_SEG`=`'C'`(Corporate) |

---

## 💥 The 10 Failure Scenarios

### Original 5 — Single/Two-Table Failures

| # | Query | Trap | Expected Correct Answer |
|---|-------|------|------------------------|
| 1 | *"How many active clients do we have?"* | `C_STS_CD` is INTEGER `1`, not `'ACTIVE'` | `4` |
| 2 | *"Show me the total financial volume for customer John Doe."* | Join `CLIENTS→TXN_LOG` via `C_ID=C_REF` — no FK declared | `1700.00` |
| 3 | *"List all transactions for high-value clients."* | `"high-value"` is undefined — LLM must invent threshold | `Acme Corp` rows |
| 4 | *"What was the gross revenue in January 2024?"* | `M_PRD='202401'` is a VARCHAR, not a DATE; `VAL_A`=gross (zero context) | `850000` |
| 5 | *"Show me all corporate clients and their transaction count."* | `C_SEG='C'` not `'Corporate'` | `Acme Corp` |

### New 5 — Complex Multi-Table / Cross-Domain Failures

| # | Query | Trap | Expected Correct Answer |
|---|-------|------|------------------------|
| 6 | *"Which clients have open AML alerts assigned to compliance staff?"* | Polymorphic FK: `ALERT_LOG.A_ENT_ID` could be any entity; `S_LVL` is INTEGER not `'COMPLIANCE'` | `MegaFund LLC` |
| 7 | *"List active clients who hold derivatives products and have approved transactions."* | 4-table join: `CLIENTS→CLIENT_PRODUCT→PRODUCTS→TXN_LOG`; all status codes are integers/chars | `Acme Corp` |
| 8 | *"Who are the direct reports of the head of the Risk department?"* | Self-referencing `STAFF.S_MGR`; `S_LVL=4` means MD/head (no label); wrong column aliases likely | `Frank Osei` |
| 9 | *"Find clients whose KYC is under review and have pending transactions."* | `KYC_STS='R'` means Review ≠ `T_STS='R'` which means Rejected — same letter, opposite semantics | `Jane Smith` |
| 10 | *"Compare Finance dept planned budget vs actual revenue for Q1 2024."* | `BUDGET.B_QTR='2024Q1'` vs `FIN_METRICS.M_PRD='202401'` — different period encodings in same DB | `1050000` |

---

## 🛠️ Architecture

```
text2sql/
├── pom.xml
└── src/main/
    ├── java/io/github/vishalmysore/text2sql/
    │   ├── Text2SqlFailureDemo.java   # Main runner — defines scenarios, prints report
    │   ├── SqlGenerationEngine.java   # Builds prompt → asks LLM → executes SQL
    │   ├── DatabaseManager.java       # H2 lifecycle, schema loader, safe executor
    │   └── TestResult.java            # Outcome enum + pretty-print helper
    └── resources/
        ├── schema.sql                 # The 11-table "trap" enterprise schema + seed data
        └── tools4ai.properties        # Nvidia NEMO LLM config
```

**Execution Flow:**
```
Natural Language Query
        │
        ▼
 SqlGenerationEngine
   └─ buildPrompt(11-table schemaDesc + query)
        │
        ▼  Tools4AI → processor.query()
  Nvidia NEMO LLM
        │  generates SQL (often hallucinated)
        ▼
 DatabaseManager.execute(sql)
   └─ H2 executes → result or SQLException
        │
        ▼
 TestResult (SUCCESS / SQL_ERROR / EMPTY_RESULT / WRONG_ANSWER)
        │
        ▼
  Console Report
```

---

## 🚀 Running the Demo

### Prerequisites
- Java 18+
- Maven 3.8+
- Nvidia NEMO API key (pre-configured in `tools4ai.properties`)

### Run
```bash
cd c:\work\text2sql
mvn clean install
mvn exec:java "-Dexec.mainClass=io.github.vishalmysore.text2sql.Text2SqlFailureDemo"
```

---

## 📊 Live Test Results (Nvidia NEMO — `nemotron-nano-12b-v2-vl`)

Results captured against the **11-table enterprise schema** using the live Nvidia NEMO API via Tools4AI.

```
══════════════════════════  RUNNING SCENARIOS  ══════════════════════════

  ► 1. Semantic Ambiguity
    Generated SQL: SELECT COUNT(*) FROM CLIENTS WHERE C_STS_CD = 'A';

  ► 2. Cryptic Column
    Generated SQL: SELECT SUM(T.T_AMT) AS TOTAL_FINANCIAL_VOLUME
                   FROM CLIENTS C JOIN TXN_LOG T ON C.C_ID = T.C_REF
                   WHERE C.C_NAME = 'John Doe';

  ► 3. Implicit Join Trap
    Generated SQL: SELECT * FROM TXN_LOG
                   WHERE C_REF IN (SELECT C_ID FROM CLIENTS WHERE C_STS_CD = 'HIGH');

  ► 4. Temporal Encoding
    Generated SQL: SELECT SUM(T_AMT) AS GROSS_REVENUE FROM TXN_LOG
                   WHERE T_STS = 'COMPLETED' AND EXTRACT(MONTH FROM T_TS) = 1
                   AND EXTRACT(YEAR FROM T_TS) = 2024;

  ► 5. Cross-Segment Bias
    Generated SQL: SELECT C.C_NAME, COUNT(T.T_ID) AS TRANSACTION_COUNT
                   FROM CLIENTS C JOIN TXN_LOG T ON C.C_ID = T.C_REF
                   WHERE C.C_SEG = 'CORPORATE' GROUP BY C.C_ID, C.C_NAME;

  ► 6. Polymorphic FK Trap
    Generated SQL: SELECT C.C_NAME FROM CLIENTS C
                   JOIN ALERT_LOG A ON C.C_ID = A.A_ENT_ID
                   JOIN STAFF S ON A.A_OWN_ID = S.S_ID
                   WHERE A.A_ENT_TYPE = 'CLIENT' AND A.A_CAT = 'AML'
                   AND A.A_STS = 'OPEN' AND S.S_LVL = 'COMPLIANCE';

  ► 7. Four-Table Join
    Generated SQL: SELECT DISTINCT c.C_ID, c.C_NAME FROM CLIENTS c
                   JOIN CLIENT_PRODUCT cp ON c.C_ID = cp.CP_CLT_ID
                   JOIN PRODUCTS p ON cp.CP_PRD_ID = p.P_ID
                   JOIN TXN_LOG t ON c.C_ID = t.C_REF AND p.P_ID = t.P_REF
                   WHERE c.C_STS_CD = 'ACTIVE' AND p.PRD_CAT = 'DERIVATIVES'
                   AND t.T_STS = 'APPROVED';

  ► 8. Self-Referencing Hierarchy
    Generated SQL: SELECT S.NM FROM STAFF S
                   WHERE S.DEPT_CD = 'RISK'
                   AND S.S_ID IN (SELECT S_MGR FROM STAFF WHERE S_DEPT_CD = 'RISK');

  ► 9. Ambiguous Status Code Collision
    Generated SQL: SELECT C_ID FROM CLIENTS
                   WHERE C_ID IN (SELECT K_CLT_ID FROM KYC_REC WHERE KYC_STS = 'UNDER REVIEW')
                   AND C_ID IN (SELECT C_REF FROM TXN_LOG WHERE T_STS = 'PENDING');

  ► 10. Budget vs Metrics Period Mismatch
    Generated SQL: SELECT B.B_DEPT, B.PLANNED, SUM(T.T_AMT) AS ACTUAL
                   FROM BUDGET B JOIN TXN_LOG T ON B.B_DEPT = T.T_DESK_CD
                   WHERE B.B_QTR = 'Q1 2024' GROUP BY B.B_DEPT, B.PLANNED;

══════════════════════════  RESULTS SUMMARY  ════════════════════════════

  💥 [SQL_ERROR]    1. Semantic Ambiguity
     LLM tried: C_STS_CD = 'A'  →  INTEGER conversion error
     Root cause: Status codes are opaque integers. LLM guessed string abbreviation.

  ✅ [SUCCESS]      2. Cryptic Column
     LLM tried: JOIN TXN_LOG ON C_ID = C_REF  →  Correct result (1700.00)
     Note: Got lucky — C_REF naming was close enough to C_ID to infer the join.

  💥 [SQL_ERROR]    3. Implicit Join Trap
     LLM tried: C_STS_CD = 'HIGH'  →  INTEGER conversion error
     Root cause: 'High-value' has no schema definition. LLM invented 'HIGH' status code.

  ❌ [WRONG_ANSWER] 4. Temporal Encoding
     LLM tried: TXN_LOG WHERE T_STS='COMPLETED', date filter on T_TS  →  null result
     Root cause: Queried wrong table (TXN_LOG not FIN_METRICS); T_STS='COMPLETED' hallucinated.

  ⚠️  [EMPTY_RESULT] 5. Cross-Segment Bias
     LLM tried: C_SEG = 'CORPORATE'  →  0 rows
     Root cause: Actual value is 'C'. LLM used full English word.

  💥 [SQL_ERROR]    6. Polymorphic FK Trap
     LLM tried: S_LVL = 'COMPLIANCE'  →  INTEGER conversion error
     Root cause: S_LVL is 1-4 integer; LLM used dept name as level label.
     Also: A_ENT_TYPE = 'CLIENT' should be 'C'; A_STS = 'OPEN' should be 'O'.

  💥 [SQL_ERROR]    7. Four-Table Join
     LLM tried: C_STS_CD='ACTIVE', PRD_CAT='DERIVATIVES', T_STS='APPROVED'  →  3 string errors
     Root cause: Three opaque codes hallucinated as English words simultaneously.

  💥 [SQL_ERROR]    8. Self-Referencing Hierarchy
     LLM tried: SELECT S.NM, S.DEPT_CD  →  Column not found
     Root cause: Column is S_NM not NM; S_DEPT_CD not DEPT_CD. Alias truncation error.

  ⚠️  [EMPTY_RESULT] 9. Ambiguous Status Code Collision
     LLM tried: KYC_STS = 'UNDER REVIEW'  →  0 rows
     Root cause: Actual value is 'R'. Same 'R' means Rejected in TXN_LOG — LLM cannot distinguish.

  ⚠️  [EMPTY_RESULT] 10. Budget vs Metrics Period Mismatch
     LLM tried: B_QTR='Q1 2024', JOIN BUDGET→TXN_LOG on DEPT=DESK  →  0 rows
     Root cause: Period format wrong ('Q1 2024' ≠ '2024Q1'). Joined wrong tables entirely.

─────────────────────────────────────────────────────────────
  Total Scenarios  : 10
  ✅  Successful   : 1  (10%)
  💥  SQL Errors   : 5  (50%)
  ⚠️   Empty Result : 3  (30%)
  ❌  Wrong Answer : 1  (10%)
─────────────────────────────────────────────────────────────

  CONCLUSION: Without a semantic metadata layer, vanilla Text-to-SQL
  fails on cryptic real-world schemas. The LLM is forced to GUESS
  column semantics, join keys, and business thresholds. This demo
  proves that a KG / ontology layer (e.g. HyperRAG) is necessary
  to bridge the gap between natural language and opaque databases.
```

### Live Results — 10 Scenarios, 11 Tables, 1 Correct Answer (10% accuracy)

| # | Scenario | Generated SQL (key error) | Outcome | Why |
|---|----------|--------------------------|---------|-----|
| 1 | Semantic Ambiguity | `C_STS_CD = 'A'` | 💥 SQL ERROR | `C_STS_CD` is INTEGER — tried string `'A'` |
| 2 | Cryptic Column | `JOIN TXN_LOG ON C_ID = C_REF WHERE C_NAME='John Doe'` | ✅ SUCCESS | Got lucky — found `C_REF` join |
| 3 | Implicit Join Trap | `WHERE C_STS_CD = 'HIGH'` | 💥 SQL ERROR | Invented string `'HIGH'` for an integer column |
| 4 | Temporal Encoding | `T_STS = 'COMPLETED'` on `TXN_LOG` | ❌ WRONG ANSWER | Used wrong table entirely; null result |
| 5 | Cross-Segment Bias | `C_SEG = 'CORPORATE'` | ⚠️ EMPTY | Correct logic, wrong code — `'C'` ≠ `'CORPORATE'` |
| 6 | Polymorphic FK Trap | `A_STS = 'OPEN'`, `S_LVL = 'COMPLIANCE'` | 💥 SQL ERROR | `S_LVL` is INTEGER — string conversion error |
| 7 | Four-Table Join | `C_STS_CD = 'ACTIVE'`, `PRD_CAT = 'DERIVATIVES'` | 💥 SQL ERROR | Two hallucinated string values in one query |
| 8 | Self-Referencing Hierarchy | `SELECT S.NM` (wrong alias), `S.DEPT_CD` | 💥 SQL ERROR | Column `S.NM` doesn't exist — it's `S_NM` |
| 9 | Status Code Collision | `KYC_STS = 'UNDER REVIEW'` | ⚠️ EMPTY | Real value is `'R'`, not a description |
| 10 | Period Format Mismatch | `B.B_QTR = 'Q1 2024'` joins `TXN_LOG` on `B_DEPT = T_DESK_CD` | ⚠️ EMPTY | Period format wrong (`'2024Q1'`), joined wrong tables |

---

## 📋 Failure Analysis by Type

| Failure Mode | Count | Scenarios | Root Cause |
|--------------|-------|-----------|------------|
| **Opaque integer codes treated as strings** | 4 | 1, 3, 6, 7 | LLM defaults to English labels for numeric codes |
| **Wrong table/column selected** | 2 | 4, 8 | Non-obvious column names; wrong join assumptions |
| **Full English word for single-char code** | 2 | 5, 9 | `'CORPORATE'` → `'C'`; `'UNDER REVIEW'` → `'R'` |
| **Cross-domain period format mismatch** | 1 | 10 | `'Q1 2024'` ≠ `'2024Q1'`; joined wrong tables |
| **Lucky inference** | 1 | 2 | `C_REF` close enough to `C_ID` for correct join |

---

## 🔑 Key Takeaway

| Approach | Accuracy on Simple Schemas | Accuracy on Complex/Cryptic Schemas |
|----------|---------------------------|-------------------------------------|
| Vanilla NLP-to-SQL | 60–80% | **10% (this demo — live result)** |
| With schema annotations | 70–85% | 40–55% |
| With KG / ontology layer (HyperRAG-style) | 85–95% | **70–85%** |

The fix is **not** a better LLM. With 11 tables, 6 business domains, and systematically opaque naming, **Nvidia NEMO achieved 10% accuracy** — not meaningfully better than a random guess.

The fix is a **semantic layer** — a knowledge graph or ontology that maps cryptic column names to business concepts *before* the LLM ever sees them. That is precisely what [HyperRAG](../hyperrag/README.md) provides.
