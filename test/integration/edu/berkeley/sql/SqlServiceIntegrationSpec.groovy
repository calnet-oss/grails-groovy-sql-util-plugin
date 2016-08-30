/*
 * Copyright (c) 2016, Regents of the University of California and
 * contributors.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 * IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package edu.berkeley.sql

import grails.test.spock.IntegrationSpec
import groovy.sql.Sql
import org.codehaus.groovy.grails.commons.GrailsApplication
import org.springframework.transaction.PlatformTransactionManager

import javax.sql.DataSource

class SqlServiceIntegrationSpec extends IntegrationSpec {
    GrailsApplication grailsApplication
    DataSource dataSource
    SqlService sqlService

    def setup() {
        def sql = new Sql(dataSource)
        sql.withTransaction {
            sql.execute("DROP TABLE IF EXISTS Test" as String)
            sql.execute("CREATE TABLE Test(key INTEGER)" as String)
        }
    }

    def cleanup() {
        def sql = new Sql(dataSource)
        sql.execute("DROP TABLE IF EXISTS Test" as String)
    }

    void "test getCurrentTransactionSql for a dataSource"() {
        when:
        Sql sql = sqlService.getCurrentTransactionSql(dataSource)

        then:
        sql
    }

    void "test withNewTransaction boundary"() {
        when:
        // Must insert something for h2 to establish a transaction_id
        assert currentSql.executeUpdate("INSERT INTO Test VALUES(1)" as String)
        def startingTxId = currentSql.firstRow("SELECT transaction_id() AS txId" as String).txId
        def newTxId = sqlService.withNewTransaction(transactionManager) {
            assert currentSql.executeUpdate("INSERT INTO Test VALUES(2)" as String)
            return currentSql.firstRow("SELECT transaction_id() AS txId" as String).txId
        }
        def endingTxId = currentSql.firstRow("SELECT transaction_id() AS txId" as String).txId

        then:
        startingTxId != newTxId
        startingTxId == endingTxId
    }

    void "test withNewTransaction exception rollback"() {
        when:
        try {
            sqlService.withNewTransaction(transactionManager) {
                assert currentSql.executeUpdate("INSERT INTO Test VALUES(2)" as String)
                throw new Exception("purposely thrown exception")
            }
        }
        catch (Exception e) {
            assert e.message == "purposely thrown exception"
        }
        def rowCount = currentSql.firstRow("SELECT count(*) AS count FROM Test" as String).count

        then:
        rowCount == 0
    }

    private PlatformTransactionManager getTransactionManager() {
        return grailsApplication.mainContext.getBean("transactionManager", PlatformTransactionManager)
    }

    private Sql getCurrentSql() {
        return sqlService.getCurrentTransactionSql(transactionManager)
    }
}
