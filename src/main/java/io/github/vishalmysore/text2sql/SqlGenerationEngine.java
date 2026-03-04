package io.github.vishalmysore.text2sql;

import com.t4a.predict.PredictionLoader;
import com.t4a.processor.AIProcessor;

import java.sql.SQLException;

/**
 * The core engine for the failure demo.
 *
 * Workflow for each test:
 * 1. Build a prompt containing the raw schema + the natural language question.
 * 2. Ask the LLM (via Tools4AI processor.query()) to generate SQL.
 * 3. Execute the SQL against H2.
 * 4. Classify the outcome: SUCCESS / SQL_ERROR / EMPTY_RESULT / WRONG_ANSWER.
 */
public class SqlGenerationEngine {

    private final AIProcessor aiProcessor;
    private final DatabaseManager db;

    public SqlGenerationEngine(DatabaseManager db) {
        System.setProperty("tools4ai.properties.path", "tools4ai.properties");
        this.aiProcessor = PredictionLoader.getInstance().createOrGetAIProcessor();
        this.db = db;
    }

    /**
     * Builds the prompt, asks the LLM, executes the SQL, and returns a TestResult.
     */
    public TestResult runScenario(String scenarioName,
            String naturalLanguageQuery,
            String correctAnswerHint) {
        System.out.println("  ► Running scenario: " + scenarioName);

        // Step 1: Build the prompt
        String prompt = buildPrompt(naturalLanguageQuery);

        // Step 2: Ask the LLM
        String generatedSql;
        try {
            generatedSql = aiProcessor.query(prompt);
        } catch (Exception e) {
            return new TestResult(scenarioName, naturalLanguageQuery,
                    "(LLM call failed: " + e.getMessage() + ")",
                    TestResult.Outcome.SQL_ERROR,
                    "LLM API error",
                    "could not contact model: " + e.getMessage());
        }

        // Strip any preamble the LLM adds before/after the SQL
        String cleanedSql = extractSql(generatedSql);
        System.out.println("    Generated SQL: " + cleanedSql.replace("\n", " ").trim());

        // Step 3: Execute
        String resultSummary;
        TestResult.Outcome outcome;
        String failureReason = null;

        try {
            resultSummary = db.execute(cleanedSql);
            if (resultSummary.startsWith("EMPTY RESULT SET")) {
                outcome = TestResult.Outcome.EMPTY_RESULT;
                failureReason = "LLM's filter logic returned zero rows. Correct answer: " + correctAnswerHint;
            } else {
                // Heuristic: if correct hint is present in the result treat as success
                if (correctAnswerHint != null
                        && resultSummary.toLowerCase().contains(correctAnswerHint.toLowerCase())) {
                    outcome = TestResult.Outcome.SUCCESS;
                } else {
                    outcome = TestResult.Outcome.WRONG_ANSWER;
                    failureReason = "Rows returned but answer is semantically incorrect. Expected hint: "
                            + correctAnswerHint;
                }
            }
        } catch (SQLException e) {
            resultSummary = "SQLException: " + e.getMessage();
            outcome = TestResult.Outcome.SQL_ERROR;
            failureReason = "H2 rejected the SQL — hallucinated column or join: " + e.getMessage();
        }

        return new TestResult(scenarioName, naturalLanguageQuery, cleanedSql,
                outcome, resultSummary, failureReason);
    }

    // -------------------------------------------------------------------------

    private String buildPrompt(String naturalLanguageQuery) {
        return """
                You are a SQL expert. Generate a valid SQL SELECT statement for the following database schema.
                Return ONLY the SQL statement — no explanation, no markdown code fences, no extra text.

                """ + db.getSchemaDescription() + """

                Question: """ + naturalLanguageQuery + """

                SQL:""";
    }

    /**
     * Extracts the first SELECT statement from the LLM response.
     * LLMs often add explanatory text before or after the SQL.
     */
    private String extractSql(String response) {
        if (response == null || response.isBlank())
            return "";

        // Remove markdown fences
        response = response.replaceAll("(?is)```sql\\s*", "").replaceAll("(?is)```\\s*", "");

        // Find the first SELECT
        int selectIdx = response.toUpperCase().indexOf("SELECT");
        if (selectIdx >= 0) {
            return response.substring(selectIdx).trim();
        }
        return response.trim();
    }
}
