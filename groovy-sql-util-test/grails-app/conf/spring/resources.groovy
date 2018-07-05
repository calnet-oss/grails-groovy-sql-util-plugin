import org.springframework.jdbc.datasource.DataSourceTransactionManager

beans = {
    transactionManager(DataSourceTransactionManager) {
        dataSource = ref('dataSource')
    }
}
