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

import grails.transaction.GrailsTransactionTemplate
import grails.transaction.Transactional
import groovy.sql.Sql
import org.grails.transaction.ChainedTransactionManager
import org.grails.transaction.GrailsTransactionAttribute
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.interceptor.RollbackRuleAttribute
import org.springframework.transaction.jta.JtaTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus

import javax.sql.DataSource
import java.sql.Connection

class SqlService {
    // handle at method level with @Transactional annotations
    static transactional = false

    @Transactional(propagation = Propagation.MANDATORY)
    Sql getCurrentTransactionSql(DataSource dataSource) {
        if (transactionManager instanceof JtaTransactionManager) {
            return new Sql(dataSource)
        } else {
            DataSourceTransactionManager txMgr = null
            if (transactionManager instanceof ChainedTransactionManager) {
                txMgr = (DataSourceTransactionManager) ((ChainedTransactionManager) transactionManager).transactionManagers.find { PlatformTransactionManager mgr ->
                    mgr instanceof DataSourceTransactionManager && ((DataSourceTransactionManager) mgr).dataSource == dataSource
                }
            } else if (transactionManager instanceof DataSourceTransactionManager) {
                txMgr = (DataSourceTransactionManager) transactionManager
            } else {
                throw new RuntimeException("Couldn't find a transaction manager that belongs to the dataSource")
            }

            return txMgr ? getCurrentTransactionSql(txMgr) : null
        }
    }

    private Connection getCurrentTransactionConnection(DefaultTransactionStatus targetTransactionStatus) {
        Connection conn = targetTransactionStatus.transaction?.connectionHolder?.connection
        if (!conn)
            throw new RuntimeException("Unable to get connection from $targetTransactionStatus")
        return conn
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Sql getCurrentTransactionSql(PlatformTransactionManager targetTransactionManager) {
        if (targetTransactionManager instanceof ChainedTransactionManager) {
            throw new RuntimeException("The passed in transaction manager can't be a ChainedTransactionManager.  Instead, pass in the native transaction manager for the dataSource or use getCurrentTransactionSql(dataSource).")
        } else if (targetTransactionManager instanceof JtaTransactionManager) {
            throw new RuntimeException("The passed in transaction manager can't be a JtaTransactionManager.  Use getCurrentTransactionSql(dataSource) instead.")
        }
        // transactionManager and transactionStatus are injected into the
        // method by the @Transactional annotation
        DefaultTransactionStatus targetTransactionStatus
        if (transactionManager instanceof ChainedTransactionManager) {
            targetTransactionStatus = findMatchingTransactionStatus(targetTransactionManager, transactionStatus)
        } else {
            // not a ChainedTransactionManager.
            // confirm targetTransactionManager is the same as the injected
            // transactionManager
            if (targetTransactionManager == transactionManager) {
                // use the injected transactionStatus for the main
                // transactionManager
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

    Sql getCurrentTransactionSql(DefaultTransactionStatus targetTransactionStatus) {
        return new Sql(getCurrentTransactionConnection(targetTransactionStatus))
    }

    /**
     * If using a ChainedTransactionManager, find the transaction status for
     * a particular transaction manager within the chain.
     *
     * @param targetTransactionManager The PlatformTransactionManager
     *        instance you would like the status for.
     * @param chainedTransactionStatus This is the current TransactionStatus
     *        for the ChainedTransactionManager.
     * @return The current TransactionStatus for the targetTransactionManager.
     */
    private DefaultTransactionStatus findMatchingTransactionStatus(PlatformTransactionManager targetTransactionManager, TransactionStatus chainedTransactionStatus) {
        for (Map.Entry<PlatformTransactionManager, TransactionStatus> mapEntry : chainedTransactionStatus.transactionStatuses) {
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

    /**
     * Uses a GrailsTransactionTemplate to execute a closure within its own
     * bounded transaction.  The transaction is guaranteed to be new upon
     * entrance and be completed (committed or rolled back) upon exit.  The
     * Closure takes one parameter: a TransactionStatus object.  All
     * Throwables from the closure cause a rollback and they are rethrown.
     *
     * @param txMgr The transaction manager to run the transaction within.
     * @param closure A Closure to execute within the transaction.
     * @return The return value from the closure.
     */
    Object withNewTransaction(PlatformTransactionManager txMgr, Closure closure) {
        return withNewTransaction(txMgr, getTransactionAttributeForNewTransaction(), closure)
    }

    /**
     * Uses a GrailsTransactionTemplate to execute a closure within its own
     * bounded transaction.  The transaction is guaranteed to be new upon
     * entrance and be completed (committed or rolled back) upon exit.  The
     * Closure takes one parameter: a TransactionStatus object.  The
     * txAttribute indicates which Throwable types cause a rollback.  All
     * Throwables are rethrown no matter whether a rollback occurred or not.
     *
     * @param txMgr The transaction manager to run the transaction within.
     * @param txAttribute Configures transaction behavior.
     * @param closure A Closure to execute within the transaction.
     * @return The return value from the closure.
     */
    Object withNewTransaction(PlatformTransactionManager txMgr, GrailsTransactionAttribute txAttribute, Closure closure) {
        GrailsTransactionTemplate txTemplate = new GrailsTransactionTemplate(txMgr, txAttribute)
        TransactionStatus txStatus = null
        try {
            Object result = txTemplate.execute { TransactionStatus status ->
                // upon entrance: guaranteed to be new
                assert status.newTransaction
                txStatus = status
                try {
                    return closure.call(txStatus)
                }
                catch (Throwable t) {
                    if (txAttribute.rollbackOn(t)) {
                        status.setRollbackOnly()
                    }
                    throw t
                }
            }
            // after exit: guaranteed to be completed
            assert txStatus?.completed
            return result
        }
        catch (Throwable t) {
            // If the TransactionAttribute is configured to rollback on this
            // throwable type, then the transaction template should have
            // already marked it for rollback on execute closure exit.
            assert txStatus?.completed
            if (txAttribute.rollbackOn(t)) {
                assert txStatus.rollbackOnly
            }
            throw t
        }
    }

    protected static GrailsTransactionAttribute getTransactionAttributeForNewTransaction() {
        GrailsTransactionAttribute txAttribute = new GrailsTransactionAttribute(TransactionDefinition.PROPAGATION_REQUIRES_NEW, [new RollbackRuleAttribute(Throwable)])
        txAttribute.inheritRollbackOnly = true
        return txAttribute
    }
}
