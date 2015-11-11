import org.springframework.jdbc.datasource.DataSourceTransactionManager

beans = {
    // this forces the use of a ChainedTransactionManager
    /* Causes problems with the tests for reasons haven't been able to figure out.
    fakeTransactionManager(DataSourceTransactionManager) {
      dataSource = new org.h2.jdbcx.JdbcDataSource([
          url: "jdbc:h2:mem:devDb2;MVCC=TRUE;LOCK_TIMEOUT=10000;DB_CLOSE_ON_EXIT=FALSE"
      ])
    }
    */
}
