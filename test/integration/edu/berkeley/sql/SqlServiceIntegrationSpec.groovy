package edu.berkeley.sql

import grails.test.spock.IntegrationSpec
import groovy.sql.Sql
import org.codehaus.groovy.grails.commons.GrailsApplication
import org.springframework.transaction.PlatformTransactionManager

import javax.sql.DataSource

class SqlServiceIntegrationSpec extends IntegrationSpec {
    static transactional = false

    GrailsApplication grailsApplication
    DataSource dataSource
    SqlService sqlService

    def setup() {
        def sql = new Sql(dataSource)
        sql.withTransaction {
            try {
                sql.execute("DROP TABLE Test")
            }
            catch (Throwable e) {
            }
        }
        sql.withTransaction {
            sql.execute("CREATE TABLE Test(key INTEGER)")
        }
    }

    def cleanup() {
        def sql = new Sql(dataSource)
        sql.withTransaction {
            try {
                sql.execute("DROP TABLE Test")
            }
            catch (Throwable e) {
            }
        }
    }

    void "test transactionManager COMMIT"() {
        when:
            execute(transactionManager) { Sql sql ->
                sql.execute("TRUNCATE TABLE Test")
            }
            execute(transactionManager) { Sql sql ->
                sql.execute("INSERT INTO Test VALUES(1)")
            }
            int count = -1
            executeGuaranteedNewTransaction { Sql sql ->
                count = sql.firstRow("SELECT count(*) AS count FROM Test").count
            }

        then:
            count == 1
    }

    void "test transactionManager ROLLBACK"() {
        when:
            execute(transactionManager) { Sql sql ->
                sql.execute("TRUNCATE TABLE Test")
            }
            try {
                execute(transactionManager) { Sql sql ->
                    sql.execute("INSERT INTO Test VALUES(1)")
                    throw new RuntimeException("purposeful rollback")
                }
            }
            catch (Exception e) {
                log.info("exception thrown as expected")
            }
            int count = -1
            executeGuaranteedNewTransaction { Sql sql ->
                count = sql.firstRow("SELECT count(*) AS count FROM Test").count
            }

        then:
            count == 0
    }

    /**
     * We would except that a method with REQUIRES_NEW that throws an exception
     * will rollback any writes it did in that method.
     *
     * If this doesn't happen, we may have a data source that doesn't support
     * nested transactions and is silently ignoring REQUIRES_NEW, which is bad.
     */
    void "test that second inner REQUIRES_NEW rolls back after exception"() {
        when:
            execute(transactionManager) { Sql sql ->
                sql.execute("TRUNCATE TABLE Test")
            }
            execute(transactionManager) { Sql sql ->
                // Here's the good transaction
                sql.execute("INSERT INTO Test VALUES(1)")

                try {
                    execute(transactionManager) { Sql sql2 ->
                        // Here's the bad transaction that should be rolled back
                        // (i.e., the following insert should be rolled back.)
                        sql2.execute("INSERT INTO Test VALUES(2)")
                        throw new RuntimeException("purposeful rollback")
                    }
                }
                catch (Exception e) {
                    log.info("exception thrown as expected")
                }
            }
            int count = -1
            int key = 0
            executeGuaranteedNewTransaction { Sql sql ->
                count = sql.firstRow("SELECT count(*) AS count FROM Test").count
                key = sql.firstRow("SELECT key FROM Test").key
            }

        then:
            // the first insert should have been committed
            // the second insert should have been rolled back
            // If count == 2, then we have a data source without nested transaction support that is ignoring nested REQUIRES_NEWs.
            count == 1
            // the key of the committed row should also be 1
            key == 1
    }

    void execute(PlatformTransactionManager targetTransactionManager, Closure closure) {
        sqlService.executeNewTransaction(targetTransactionManager, closure)
    }

    void executeGuaranteedNewTransaction(Closure closure) {
        sqlService.executeGuaranteedNewTransaction(getNewSessionSql(), closure)
    }

    Sql getNewSessionSql() {
        return new Sql(dataSource)
    }

    PlatformTransactionManager getTransactionManager() {
        return grailsApplication.mainContext.getBean("transactionManager")
    }
}
