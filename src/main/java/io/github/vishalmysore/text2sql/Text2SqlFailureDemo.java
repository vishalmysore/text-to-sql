package io.github.vishalmysore.text2sql;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ╔═══════════════════════════════════════════════════════════╗
 * ║ Text-to-SQL Failure Demo ║
 * ║ "Why vanilla NLP-to-SQL breaks on real-world schemas" ║
 * ╚═══════════════════════════════════════════════════════════╝
 *
 * This demo builds a deliberately cryptic H2 schema ("trap schema"),
 * then asks an Nvidia NEMO LLM to generate SQL for plain-English queries.
 * Each scenario is designed to expose a specific failure mode.
 *
 * Failure modes covered:
 * 1. Semantic Ambiguity - opaque status codes (C_STS_CD)
 * 2. Cryptic Columns - missing FK clues, zero-context column names (VAL_A)
 * 3. Implicit Join Trap - "high-value" requires business-defined threshold
 * 4. Temporal Encoding - date stored as YYYYMM string, not a DATE
 * 5. Cross-Segment Bias - segment codes 'P','C','I' with no semantics in schema
 */
public class Text2SqlFailureDemo {

    // Correct answers for validation (simplified ground truth)
    private static final Map<String, String> CORRECT_ANSWERS = Map.of(
            "Semantic Ambiguity", "4", // 4 active clients (C_STS_CD = 1)
            "Cryptic Column", "John Doe", // John Doe is in CLIENTS
            "Implicit Join Trap", "Acme Corp", // Acme Corp gross=850000
            "Temporal Encoding", "202401", // period string format
            "Cross-Segment Bias", "Corporate" // segment 'C' means Corporate
    );

    public static void main(String[] args) throws SQLException {
        printBanner();

        // ── 1. Boot DB ──
        DatabaseManager db = new DatabaseManager();
        db.init();

        // ── 2. Boot LLM Engine ──
        SqlGenerationEngine engine = new SqlGenerationEngine(db);

        // ── 3. Define scenarios ──
        List<Scenario> scenarios = defineScenarios();

        // ── 4. Run and collect results ──
        System.out.println("\n══════════════════════════  RUNNING SCENARIOS  ══════════════════════════\n");
        List<TestResult> results = new ArrayList<>();
        for (Scenario s : scenarios) {
            TestResult result = engine.runScenario(s.name(), s.query(), s.correctHint());
            results.add(result);
        }

        // ── 5. Report ──
        printReport(results);

        db.close();
    }

    // ── Scenario Definitions ───────────────────────────────────────────────

    record Scenario(String name, String query, String correctHint) {
    }

    private static List<Scenario> defineScenarios() {
        return List.of(

                // ── ORIGINAL 5 ──────────────────────────────────────────────────

                new Scenario(
                        "1. Semantic Ambiguity",
                        "How many active clients do we have?",
                        "4" // C_STS_CD=1; LLM will try string 'ACTIVE'
                ),

                new Scenario(
                        "2. Cryptic Column",
                        "Show me the total financial volume for customer John Doe.",
                        "1700" // TXN_LOG amounts for C_ID=1; LLM may hit FIN_METRICS wrong join
                ),

                new Scenario(
                        "3. Implicit Join Trap",
                        "List all transactions for high-value clients.",
                        "Acme Corp" // 'high-value' undefined; LLM must invent criteria
                ),

                new Scenario(
                        "4. Temporal Encoding",
                        "What was the gross revenue in January 2024?",
                        "850000" // M_PRD='202401' VARCHAR; VAL_A=gross — double ambiguity
                ),

                new Scenario(
                        "5. Cross-Segment Bias",
                        "Show me all corporate clients and their transaction count.",
                        "Acme Corp" // C_SEG='C'; LLM tries 'Corporate'
                ),

                // ── NEW 5: COMPLEX MULTI-TABLE SCENARIOS ────────────────────────

                new Scenario(
                        "6. Polymorphic FK Trap",
                        "Which clients have open AML alerts assigned to compliance staff?",
                        "MegaFund" // ALERT_LOG → A_ENT_TYPE='C', A_CAT='AML', A_STS='O'; join STAFF on
                                   // S_DEPT_CD='CMPL'
                ),

                new Scenario(
                        "7. Four-Table Join",
                        "List all active clients who hold derivatives products and have approved transactions.",
                        "Acme Corp" // CLIENTS→CLIENT_PRODUCT→PRODUCTS(PRD_CAT='DRV')→TXN_LOG(T_STS='A')
                ),

                new Scenario(
                        "8. Self-Referencing Hierarchy",
                        "Who are the direct reports of the head of the Risk department?",
                        "Frank Osei" // STAFF self-join on S_MGR; S_DEPT_CD='RISK'; S_LVL=4 is MD (head)
                ),

                new Scenario(
                        "9. Ambiguous Status Code Collision",
                        "Find all clients whose KYC is under review and have pending transactions.",
                        "Jane Smith" // KYC_STS='R'(Review) vs TXN_LOG.T_STS='P'(Pending) — 'R' means DIFFERENT
                                     // things
                ),

                new Scenario(
                        "10. Budget vs Metrics Period Mismatch",
                        "Compare the Finance department's planned budget against actual revenue for Q1 2024.",
                        "1050000" // BUDGET.B_QTR='2024Q1' vs FIN_METRICS.M_PRD='202401' — different period
                                  // formats!
                ));
    }

    // ── Reporting ──────────────────────────────────────────────────────────

    private static void printReport(List<TestResult> results) {
        System.out.println("\n══════════════════════════  RESULTS SUMMARY  ════════════════════════════\n");

        long success = results.stream().filter(r -> r.getOutcome() == TestResult.Outcome.SUCCESS).count();
        long sqlErr = results.stream().filter(r -> r.getOutcome() == TestResult.Outcome.SQL_ERROR).count();
        long empty = results.stream().filter(r -> r.getOutcome() == TestResult.Outcome.EMPTY_RESULT).count();
        long wrong = results.stream().filter(r -> r.getOutcome() == TestResult.Outcome.WRONG_ANSWER).count();
        long total = results.size();

        for (TestResult r : results) {
            r.print();
        }

        System.out.println("─────────────────────────────────────────────────────────────");
        System.out.printf("  Total Scenarios  : %d%n", total);
        System.out.printf("  ✅  Successful   : %d  (%.0f%%)%n", success, pct(success, total));
        System.out.printf("  💥  SQL Errors   : %d  (%.0f%%)%n", sqlErr, pct(sqlErr, total));
        System.out.printf("  ⚠️   Empty Result : %d  (%.0f%%)%n", empty, pct(empty, total));
        System.out.printf("  ❌  Wrong Answer : %d  (%.0f%%)%n", wrong, pct(wrong, total));
        System.out.println("─────────────────────────────────────────────────────────────");
        System.out.println();
        System.out.println("  CONCLUSION: Without a semantic metadata layer, vanilla Text-to-SQL");
        System.out.println("  fails on cryptic real-world schemas. The LLM is forced to GUESS");
        System.out.println("  column semantics, join keys, and business thresholds. This demo");
        System.out.println("  proves that a KG / ontology layer (e.g. HyperRAG) is necessary");
        System.out.println("  to bridge the gap between natural language and opaque databases.");
        System.out.println();
    }

    private static double pct(long count, long total) {
        return total == 0 ? 0.0 : (count * 100.0 / total);
    }

    private static void printBanner() {
        System.out.println("""
                ╔═══════════════════════════════════════════════════════════════╗
                ║           Text-to-SQL Failure Demo                           ║
                ║    "Why NLP-to-SQL Breaks on Complex / Cryptic Schemas"      ║
                ║                                                               ║
                ║  Schema  : H2 in-memory (CLIENTS, TXN_LOG, FIN_METRICS)      ║
                ║  LLM     : Nvidia NEMO (via Tools4AI)                        ║
                ║  Purpose : Demonstrate hallucinated SQL on opaque schemas     ║
                ╚═══════════════════════════════════════════════════════════════╝
                """);
    }
}
