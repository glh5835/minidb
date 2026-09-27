package minidb.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** JDBC DatabaseMetaData：极简实现（表清单/产品信息）。 */
public final class MiniDbDatabaseMetaData implements java.sql.DatabaseMetaData {
    private final MiniDbConnection conn;

    MiniDbDatabaseMetaData(MiniDbConnection conn) {
        this.conn = conn;
    }

    @Override
    public String getURL() {
        return MiniDbDriver.URL_PREFIX + "<database>";
    }

    @Override
    public String getUserName() {
        return "minidb";
    }

    @Override
    public String getDatabaseProductName() {
        return "MiniDB";
    }

    @Override
    public String getDatabaseProductVersion() {
        return "0.6.0";
    }

    @Override
    public String getDriverName() {
        return "MiniDB JDBC Driver";
    }

    @Override
    public String getDriverVersion() {
        return "0.6.0";
    }

    @Override
    public int getDriverMajorVersion() {
        return 0;
    }

    @Override
    public int getDriverMinorVersion() {
        return 6;
    }

    @Override
    public boolean usesLocalFiles() {
        return true;
    }

    @Override
    public boolean usesLocalFilePerTable() {
        return false;
    }

    @Override
    public boolean supportsMixedCaseQuotedIdentifiers() {
        return true;
    }

    @Override
    public boolean supportsTransactions() {
        return true;
    }

    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern,
                               String[] types) throws SQLException {
        List<String> cols = List.of("TABLE_NAME");
        List<Object[]> rows = new ArrayList<>();
        for (String name : conn.db().tableNames()) {
            if (tableNamePattern == null || name.matches(tableNamePattern.replace("%", ".*")))
                rows.add(new Object[]{name});
        }
        return new MiniDbResultSet(null, new minidb.exec.Executor.Result(cols, rows, null));
    }

    /** 其余方法不支持。 */
    private SQLException uns() {
        return new SQLException("MiniDB 不支持该 DatabaseMetaData 特性");
    }

    @Override
    public boolean allProceduresAreCallable() { return false; }

    @Override
    public boolean allTablesAreSelectable() { return true; }

    @Override
    public boolean nullsAreSortedHigh() { return false; }

    @Override
    public boolean nullsAreSortedLow() { return true; }

    @Override
    public boolean nullsAreSortedAtStart() { return false; }

    @Override
    public boolean nullsAreSortedAtEnd() { return false; }

    @Override
    public String getIdentifierQuoteString() { return "'"; }

    @Override
    public String getSQLKeywords() { return ""; }

    @Override
    public String getNumericFunctions() { return ""; }

    @Override
    public String getStringFunctions() { return ""; }

    @Override
    public String getSystemFunctions() { return ""; }

    @Override
    public String getTimeDateFunctions() { return ""; }

    @Override
    public String getSearchStringEscape() { return "\\"; }

    @Override
    public String getExtraNameCharacters() { return ""; }

    @Override
    public boolean supportsAlterTableWithAddColumn() { return false; }

    @Override
    public boolean supportsAlterTableWithDropColumn() { return false; }

    @Override
    public boolean supportsColumnAliasing() { return true; }

    @Override
    public boolean nullPlusNonNullIsNull() { return false; }

    @Override
    public boolean supportsConvert() { return false; }

    @Override
    public boolean supportsConvert(int fromType, int toType) { return false; }

    @Override
    public boolean supportsTableCorrelationNames() { return false; }

    @Override
    public boolean supportsDifferentTableCorrelationNames() { return false; }

    @Override
    public boolean supportsExpressionsInOrderBy() { return true; }

    @Override
    public boolean supportsOrderByUnrelated() { return true; }

    @Override
    public boolean supportsGroupBy() { return true; }

    @Override
    public boolean supportsGroupByUnrelated() { return true; }

    @Override
    public boolean supportsGroupByBeyondSelect() { return true; }

    @Override
    public boolean supportsLikeEscapeClause() { return false; }

    @Override
    public boolean supportsMultipleResultSets() { return false; }

    @Override
    public boolean supportsMultipleTransactions() { return true; }

    @Override
    public boolean supportsNonNullableColumns() { return false; }

    @Override
    public boolean supportsMinimumSQLGrammar() { return true; }

    @Override
    public boolean supportsCoreSQLGrammar() { return false; }

    @Override
    public boolean supportsExtendedSQLGrammar() { return false; }

    @Override
    public boolean supportsANSI92EntryLevelSQL() { return false; }

    @Override
    public boolean supportsANSI92IntermediateSQL() { return false; }

    @Override
    public boolean supportsANSI92FullSQL() { return false; }

    @Override
    public boolean supportsIntegrityEnhancementFacility() { return false; }

    @Override
    public boolean supportsOuterJoins() { return true; }

    @Override
    public boolean supportsFullOuterJoins() { return false; }

    @Override
    public boolean supportsLimitedOuterJoins() { return true; }

    @Override
    public ResultSet getSchemas() { return empty(); }

    @Override
    public ResultSet getCatalogs() { return empty(); }

    @Override
    public ResultSet getTableTypes() { return empty(); }

    @Override
    public ResultSet getColumns(String c, String s, String t, String col) throws SQLException { throw uns(); }

    @Override
    public ResultSet getColumnPrivileges(String c, String s, String t, String col) throws SQLException { throw uns(); }

    @Override
    public ResultSet getTablePrivileges(String c, String s, String t) throws SQLException { throw uns(); }

    @Override
    public ResultSet getBestRowIdentifier(String c, String s, String t, int scope, boolean nullable) throws SQLException { throw uns(); }

    @Override
    public ResultSet getVersionColumns(String c, String s, String t) throws SQLException { throw uns(); }

    @Override
    public ResultSet getPrimaryKeys(String c, String s, String t) throws SQLException { throw uns(); }

    @Override
    public ResultSet getImportedKeys(String c, String s, String t) throws SQLException { throw uns(); }

    @Override
    public ResultSet getExportedKeys(String c, String s, String t) throws SQLException { throw uns(); }

    @Override
    public ResultSet getCrossReference(String pc, String ps, String pt, String fc, String fs, String ft) throws SQLException { throw uns(); }

    @Override
    public ResultSet getTypeInfo() throws SQLException { throw uns(); }

    @Override
    public ResultSet getIndexInfo(String c, String s, String t, boolean unique, boolean approximate) throws SQLException { throw uns(); }

    @Override
    public boolean supportsResultSetType(int type) { return type == ResultSet.TYPE_FORWARD_ONLY; }

    @Override
    public boolean supportsResultSetConcurrency(int type, int concurrency) {
        return type == ResultSet.TYPE_FORWARD_ONLY && concurrency == ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public boolean ownUpdatesAreVisible(int type) { return false; }

    @Override
    public boolean ownDeletesAreVisible(int type) { return false; }

    @Override
    public boolean ownInsertsAreVisible(int type) { return false; }

    @Override
    public boolean othersUpdatesAreVisible(int type) { return false; }

    @Override
    public boolean othersDeletesAreVisible(int type) { return false; }

    @Override
    public boolean othersInsertsAreVisible(int type) { return false; }

    @Override
    public boolean updatesAreDetected(int type) { return false; }

    @Override
    public boolean deletesAreDetected(int type) { return false; }

    @Override
    public boolean insertsAreDetected(int type) { return false; }

    @Override
    public boolean supportsBatchUpdates() { return false; }

    @Override
    public java.sql.Connection getConnection() { return conn; }

    // 其余 JDBC 4.x 方法：不支持
    @Override public ResultSet getAttributes(String c, String s, String t, String a) throws SQLException { throw uns(); }
    @Override public boolean supportsSavepoints() { return false; }
    @Override public boolean supportsNamedParameters() { return false; }
    @Override public boolean supportsMultipleOpenResults() { return false; }
    @Override public boolean supportsGetGeneratedKeys() { return false; }
    @Override public java.sql.RowIdLifetime getRowIdLifetime() throws SQLException { throw uns(); }
    @Override public ResultSet getSuperTypes(String c, String s, String t) throws SQLException { throw uns(); }
    @Override public ResultSet getSuperTables(String c, String s, String t) throws SQLException { throw uns(); }
    @Override public ResultSet getUDTs(String c, String s, String t, int[] types) throws SQLException { throw uns(); }
    @Override public ResultSet getProcedureColumns(String c, String s, String p, String col) throws SQLException { throw uns(); }
    @Override public ResultSet getProcedures(String c, String s, String p) throws SQLException { throw uns(); }
    @Override public ResultSet getClientInfoProperties() throws SQLException { throw uns(); }
    @Override public ResultSet getFunctionColumns(String c, String s, String p, String col) throws SQLException { throw uns(); }
    @Override public ResultSet getFunctions(String c, String s, String p) throws SQLException { throw uns(); }
    @Override public boolean supportsStoredFunctionsUsingCallSyntax() { return false; }
    @Override public boolean generatedKeyAlwaysReturned() { return false; }
    @Override public ResultSet getPseudoColumns(String c, String s, String t, String col) throws SQLException { throw uns(); }
    @Override public boolean autoCommitFailureClosesAllResultSets() { return false; }
    @Override public boolean supportsStatementPooling() { return false; }
    @Override public boolean dataDefinitionIgnoredInTransactions() { return false; }
    @Override public boolean dataDefinitionCausesTransactionCommit() { return false; }
    @Override public boolean supportsDataManipulationTransactionsOnly() { return false; }
    @Override public boolean supportsDataDefinitionAndDataManipulationTransactions() { return false; }
    @Override public boolean supportsTransactionIsolationLevel(int level) { return true; }
    @Override public boolean supportsOpenStatementsAcrossCommit() { return false; }
    @Override public boolean supportsOpenStatementsAcrossRollback() { return false; }
    @Override public boolean supportsOpenCursorsAcrossCommit() { return false; }
    @Override public boolean supportsOpenCursorsAcrossRollback() { return false; }
    @Override public boolean supportsUnion() { return false; }
    @Override public boolean supportsUnionAll() { return false; }
    @Override public String getCatalogSeparator() { return "."; }
    @Override public String getCatalogTerm() { return "catalog"; }
    @Override public String getProcedureTerm() { return "procedure"; }
    @Override public String getSchemaTerm() { return "schema"; }
    @Override public boolean isCatalogAtStart() { return true; }
    @Override public boolean isReadOnly() { return false; }
    @Override public boolean storesLowerCaseIdentifiers() { return false; }
    @Override public boolean storesLowerCaseQuotedIdentifiers() { return false; }
    @Override public boolean storesMixedCaseIdentifiers() { return true; }
    @Override public boolean storesMixedCaseQuotedIdentifiers() { return true; }
    @Override public boolean storesUpperCaseIdentifiers() { return false; }
    @Override public boolean storesUpperCaseQuotedIdentifiers() { return false; }
    @Override public boolean supportsMixedCaseIdentifiers() { return true; }
    @Override public boolean supportsPositionedUpdate() { return false; }
    @Override public boolean supportsPositionedDelete() { return false; }
    @Override public boolean supportsCatalogsInPrivilegeDefinitions() { return false; }
    @Override public boolean supportsCatalogsInIndexDefinitions() { return false; }
    @Override public boolean supportsCatalogsInTableDefinitions() { return false; }
    @Override public boolean supportsCatalogsInProcedureCalls() { return false; }
    @Override public boolean supportsCatalogsInDataManipulation() { return false; }
    @Override public boolean supportsSchemasInPrivilegeDefinitions() { return false; }
    @Override public boolean supportsSchemasInIndexDefinitions() { return false; }
    @Override public boolean supportsSchemasInTableDefinitions() { return false; }
    @Override public boolean supportsSchemasInProcedureCalls() { return false; }
    @Override public boolean supportsSchemasInDataManipulation() { return false; }
    @Override public boolean supportsStoredProcedures() { return false; }
    @Override public boolean supportsSelectForUpdate() { return false; }
    @Override public boolean supportsSubqueriesInQuantifieds() { return false; }
    @Override public boolean supportsSubqueriesInIns() { return false; }
    @Override public boolean supportsSubqueriesInExists() { return false; }
    @Override public boolean supportsSubqueriesInComparisons() { return false; }
    @Override public boolean supportsCorrelatedSubqueries() { return false; }
    @Override public int getDefaultTransactionIsolation() { return java.sql.Connection.TRANSACTION_READ_COMMITTED; }
    @Override public ResultSet getSchemas(String c, String s) throws SQLException { throw uns(); }
    @Override public boolean supportsResultSetHoldability(int h) { return h == ResultSet.CLOSE_CURSORS_AT_COMMIT; }
    @Override public int getResultSetHoldability() { return ResultSet.CLOSE_CURSORS_AT_COMMIT; }
    @Override public int getMaxBinaryLiteralLength() { return 0; }
    @Override public int getMaxCharLiteralLength() { return 0; }
    @Override public int getMaxColumnNameLength() { return 0; }
    @Override public int getMaxColumnsInGroupBy() { return 0; }
    @Override public int getMaxColumnsInIndex() { return 0; }
    @Override public int getMaxColumnsInOrderBy() { return 0; }
    @Override public int getMaxColumnsInSelect() { return 0; }
    @Override public int getMaxColumnsInTable() { return 0; }
    @Override public int getMaxConnections() { return 0; }
    @Override public int getMaxCursorNameLength() { return 0; }
    @Override public int getMaxIndexLength() { return 0; }
    @Override public int getMaxSchemaNameLength() { return 0; }
    @Override public int getMaxProcedureNameLength() { return 0; }
    @Override public int getMaxCatalogNameLength() { return 0; }
    @Override public int getMaxRowSize() { return 0; }
    @Override public boolean doesMaxRowSizeIncludeBlobs() { return false; }
    @Override public int getMaxStatementLength() { return 0; }
    @Override public int getMaxStatements() { return 0; }
    @Override public int getMaxTableNameLength() { return 0; }
    @Override public int getMaxTablesInSelect() { return 0; }
    @Override public int getMaxUserNameLength() { return 0; }
    @Override public int getDatabaseMajorVersion() { return 0; }
    @Override public int getDatabaseMinorVersion() { return 6; }
    @Override public int getJDBCMajorVersion() { return 4; }
    @Override public int getJDBCMinorVersion() { return 2; }
    @Override public int getSQLStateType() { return sqlStateSQL; }
    @Override public boolean locatorsUpdateCopy() { return false; }

    private ResultSet empty() {
        return new MiniDbResultSet(null, new minidb.exec.Executor.Result(List.of(), new ArrayList<>(), null));
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("不支持 unwrap");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
