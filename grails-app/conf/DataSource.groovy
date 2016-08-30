environments {
    test {
        /**
         * We need a persisted test database because the transaction_id()
         * function only works for persisted databases -- it doesn't work
         * for "in-memory" databases.  We need transaction_id() for good
         * transaction testing.
         */
        dataSource {
            pooled = true
            jmxExport = true
            driverClassName = "org.h2.Driver"
            username = "sa"
            password = ""
            autoCommit = false
            dbCreate = "create-drop"
            url = "jdbc:h2:build/test-results/tmp/testDb;MVCC=TRUE;LOCK_TIMEOUT=100000;DB_CLOSE_ON_EXIT=FALSE"
        }
    }
}
