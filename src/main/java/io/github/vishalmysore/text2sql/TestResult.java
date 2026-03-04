package io.github.vishalmysore.text2sql;

import lombok.Getter;

/**
 * Represents the result of a single Text-to-SQL test scenario.
 * Captures the generated SQL, execution result, and failure classification.
 */
@Getter
public class TestResult {

    public enum Outcome {
        SUCCESS, // SQL ran and returned plausible data
        SQL_ERROR, // SQL threw a SQLException (bad column, bad join, etc.)
        EMPTY_RESULT, // SQL ran but returned 0 rows (wrong filter logic)
        WRONG_ANSWER // SQL ran but the answer is semantically incorrect
    }

    private final String scenarioName;
    private final String naturalLanguageQuery;
    private final String generatedSql;
    private final Outcome outcome;
    private final String resultSummary;
    private final String failureReason;

    public TestResult(String scenarioName,
            String naturalLanguageQuery,
            String generatedSql,
            Outcome outcome,
            String resultSummary,
            String failureReason) {
        this.scenarioName = scenarioName;
        this.naturalLanguageQuery = naturalLanguageQuery;
        this.generatedSql = generatedSql;
        this.outcome = outcome;
        this.resultSummary = resultSummary;
        this.failureReason = failureReason;
    }

    public void print() {
        String icon = switch (outcome) {
            case SUCCESS -> "✅";
            case SQL_ERROR -> "💥";
            case EMPTY_RESULT -> "⚠️ ";
            case WRONG_ANSWER -> "❌";
        };

        System.out.println("  " + icon + " [" + outcome + "] " + scenarioName);
        System.out.println("     NL Query  : " + naturalLanguageQuery);
        System.out.println("     Generated : " + generatedSql.replace("\n", " ").trim());
        System.out.println("     Result    : " + resultSummary);
        if (failureReason != null && !failureReason.isBlank()) {
            System.out.println("     Reason    : " + failureReason);
        }
        System.out.println();
    }
}
