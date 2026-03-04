package io.github.vishalmysore.text2sql;

import java.sql.*;

/**
 * Manages the H2 in-memory database lifecycle.
 * Initialises the "failure-by-design" schema on startup.
 */
public class DatabaseManager {

    private static final String JDBC_URL = "jdbc:h2:mem:trapdb;DB_CLOSE_DELAY=-1;INIT=RUNSCRIPT FROM 'classpath:schema.sql'";
    private static final String USER = "sa";
    private static final String PASS = "";

    private Connection connection;

    public void init() throws SQLException {
        connection = DriverManager.getConnection(JDBC_URL, USER, PASS);
        System.out.println("  [DB] H2 in-memory database initialised. Schema loaded from schema.sql.");
    }

    /**
     * Executes the given SQL and returns a human-readable summary of the result.
     * Throws SQLException if the statement is malformed — intentional for the demo.
     */
    public String execute(String sql) throws SQLException {
        String trimmed = sql.trim();

        // Strip markdown code fences that some LLMs may include
        trimmed = stripMarkdownFences(trimmed);

        // Only allow SELECT for safety
        if (!trimmed.toUpperCase().startsWith("SELECT")) {
            throw new SQLException("Only SELECT statements are permitted in this demo.");
        }

        try (Statement stmt = connection.createStatement();
                ResultSet rs = stmt.executeQuery(trimmed)) {

            ResultSetMetaData meta = rs.getMetaData();
            int cols = meta.getColumnCount();
            StringBuilder sb = new StringBuilder();

            // Build header
            for (int i = 1; i <= cols; i++) {
                sb.append(meta.getColumnLabel(i));
                if (i < cols)
                    sb.append(" | ");
            }
            sb.append("\n").append("-".repeat(60)).append("\n");

            int rowCount = 0;
            while (rs.next()) {
                for (int i = 1; i <= cols; i++) {
                    sb.append(rs.getString(i));
                    if (i < cols)
                        sb.append(" | ");
                }
                sb.append("\n");
                rowCount++;
                if (rowCount >= 10) { // cap output at 10 rows
                    sb.append("... (").append(rowCount).append("+rows truncated)\n");
                    break;
                }
            }

            if (rowCount == 0) {
                return "EMPTY RESULT SET (0 rows)";
            }

            return sb.toString();
        }
    }

    /**
     * Returns a schema string suitable for inclusion in an LLM prompt.
     * This is exactly what a vanilla Text-to-SQL approach would give the model.
     */
    public String getSchemaDescription() {
        return """
                Database tables and columns (H2 in-memory). No additional metadata provided.

                CLIENTS(C_ID, C_NAME, C_STS_CD, C_SEG, C_ONBD_DT, C_MGR_REF)
                CONTACT_INFO(X_ID, X_ENT_ID, X_ENT_TYPE, X_CHANNEL, X_VAL, X_PREF)
                STAFF(S_ID, S_NM, S_LVL, S_DEPT_CD, S_MGR)
                PRODUCTS(P_ID, P_CODE, PRD_CAT, PRD_DESC, PRD_RK, PRD_FEE_BP)
                CLIENT_PRODUCT(CP_ID, CP_CLT_ID, CP_PRD_ID, CP_ALLOC, CP_DT_FROM, CP_DT_TO)
                TXN_LOG(T_ID, C_REF, P_REF, T_AMT, T_TYPE, T_STS, T_TS, T_CCY, T_DESK_CD)
                TXN_AUDIT(TA_ID, TA_TXN_REF, TA_ACT, TA_BY, TA_TS, TA_NOTE)
                FIN_METRICS(M_ID, M_ENT_ID, M_ENT_TYPE, M_PRD, VAL_A, VAL_B, VAL_C, M_DATE)
                BUDGET(B_ID, B_DEPT, B_QTR, PLANNED, ACTUAL, DELTA)
                KYC_REC(K_ID, K_CLT_ID, KYC_LVL, KYC_STS, KYC_EXP_DT, K_RVW_BY)
                ALERT_LOG(A_ID, A_ENT_ID, A_ENT_TYPE, A_SVRTY, A_CAT, A_STS, A_TS, A_OWN_ID)
                """;
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {
        }
    }

    private String stripMarkdownFences(String sql) {
        // Remove ```sql ... ``` fences
        sql = sql.replaceAll("(?is)```sql\\s*", "").replaceAll("(?is)```\\s*", "");
        return sql.trim();
    }
}
