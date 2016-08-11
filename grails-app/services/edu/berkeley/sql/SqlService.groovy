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

import grails.transaction.Transactional
import groovy.sql.Sql

//import org.springframework.transaction.annotation.Transactional
import org.codehaus.groovy.grails.transaction.ChainedTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.support.DefaultTransactionStatus

import java.sql.Connection

class SqlService {
    // handle at method level with @Transactional annotations
    static transactional = false

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception)
    void executeNewTransaction(PlatformTransactionManager _transactionManager, Closure closure) {
        closure(getCurrentTransactionSql(_transactionManager))
    }

    // We want to open our own managed transaction, so ensure there isn't a
    // managed transaction already active.
    @Transactional(propagation = Propagation.NEVER)
    void executeGuaranteedNewTransaction(Sql sql, Closure closure) {
        // Use Sql-based transaction to guarantee we are opening a new transaction here.
        sql.withTransaction { Connection conn ->
            closure(new Sql(conn))
        }
    }

    private Connection getCurrentTransactionConnection(DefaultTransactionStatus targetTransactionStatus) {
        Connection conn = targetTransactionStatus.transaction?.connectionHolder?.connection
        if (!conn)
            throw new RuntimeException("Unable to get connection from $targetTransactionStatus")
        return conn
    }

    Sql getCurrentTransactionSql(DefaultTransactionStatus targetTransactionStatus) {
        return new Sql(getCurrentTransactionConnection(targetTransactionStatus))
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Sql getCurrentTransactionSql(PlatformTransactionManager targetTransactionManager) {
        // transactionManager and transactionStatus is injected into the method by the @Transactional annotation
        DefaultTransactionStatus targetTransactionStatus
        if (transactionManager instanceof ChainedTransactionManager) {
            targetTransactionStatus = findMatchingTransactionStatus(targetTransactionManager, transactionStatus)
        } else {
            // not a ChainedTransactionManager.
            // confirm targetTransactionManager is the same as the injected transactionManager
            if (targetTransactionManager == transactionManager) {
                // use the injected transactionStatus for the main transactionManager
                if (transactionStatus instanceof DefaultTransactionStatus) {
                    targetTransactionStatus = transactionStatus
                } else {
                    throw new RuntimeException("Found a matching TransactionStatus, but it is not an instanceof DefaultTransactionStatus: ${transactionStatus.getClass().name}")
                }
            } else {
                throw new RuntimeException("targetTransactionManager=$targetTransactionManager, transactionManager=$transactionManager, platformTransactionManager is not a ChainedTransactionManager, nor is targetTransactionManager the same as the injected transactionManager bean")
            }
        }

        return getCurrentTransactionSql(targetTransactionStatus)
    }

    /**
     * If using a ChainedTransactionManager, find the transaction status for a particular transaction manager within the chain.
     *
     * @param targetTransactionManager The PlatformTransactionManager instance you would like the status for.
     * @param chainedTransactionStatus This is the current TransactionStatus for the ChainedTransactionManager.
     * @return The current TransactionStatus for the targetTransactionManager.
     */
    protected DefaultTransactionStatus findMatchingTransactionStatus(PlatformTransactionManager targetTransactionManager, TransactionStatus chainedTransactionStatus) {
        for (Map.Entry<PlatformTransactionManager, TransactionStatus> mapEntry : chainedTransactionStatus.transactionStatuses) {
            log.info("Checking ${mapEntry.key} and ${targetTransactionManager}")
            if (mapEntry.key == targetTransactionManager) {
                TransactionStatus foundStatus = mapEntry.value
                if (foundStatus instanceof DefaultTransactionStatus) {
                    return foundStatus
                } else {
                    throw new RuntimeException("Found a matching TransactionStatus, but it is not an instanceof DefaultTransactionStatus: ${foundStatus.getClass().name}")
                }
            }
        }
        throw new RuntimeException("Could not find a transaction manager within the chain that matches $targetTransactionManager")
    }
}
