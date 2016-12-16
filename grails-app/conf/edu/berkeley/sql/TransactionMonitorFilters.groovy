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

import org.apache.commons.logging.Log
import org.codehaus.groovy.grails.transaction.ChainedTransactionManager
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.context.support.GenericApplicationContext
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager

import javax.annotation.PostConstruct
import javax.sql.DataSource

/**
 * Grails filter that logs an error message if transactions are being
 * leaked.  A transaction leak happens when a web request starts a
 * transaction but doesn't complete it.  This happens because Grails uses
 * TransactionAwareDataSourceProxy for dataSources.
 */
class TransactionMonitorFilters implements ApplicationContextAware {
    static final String DEFAULT_BEAN_NAME = "transactionMonitorFilters"
    private GenericApplicationContext applicationContext
    List<DataSourceTransactionManager> dataSourceTransactionManagers
    Log logger
    String beanName

    void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = (GenericApplicationContext) applicationContext
    }

    @PostConstruct
    void postConstruct() {
        if (!beanName) {
            beanName = DEFAULT_BEAN_NAME
        }
        registerSelf((GenericApplicationContext) applicationContext)

        if (!logger) {
            if (hasProperty("log")) {
                logger = properties.log
            }
        }

        initializeFilters()
    }

    /**
     * Register self as a bean in the application context.
     */
    private void registerSelf(GenericApplicationContext applicationContext) {
        applicationContext.beanFactory.registerSingleton(beanName, this)
    }

    void setBeanName(String beanName) {
        if (dataSourceTransactionManagers) {
            throw new IllegalStateException("Cannot set bean name after bean has already been constructed and registered with the application context.")
        }
        this.beanName = beanName
    }

    static enum FilterPoint {
        BEFORE, AFTERVIEW
    }

    /**
     * No dataSource transaction should be bound to the current thread at
     * BOTH the beginning of a web request and at the end of a web request. 
     * If there is something bound at the beginning or end then there's a
     * "transaction leak" where the transaction was started but not
     * completed.
     *
     * If the warning message appears at the beginning of the web request,
     * that means a previous request didn't clean up its transaction
     * properly.
     *
     * If the warning message appears at the end of the web request but not
     * at the beginning, that means the current web request leaked the
     * transaction.
     */
    def filters = {
        all(controller: '*', action: '*') {
            before = {
                // checks that no dataSource transaction resource bound to
                // this thread at beginning of request
                warnIfNotClean(FilterPoint.BEFORE)
                return true
            }
            afterView = {
                // checks that no dataSource transaction resource bound to
                // this thread at end of request
                warnIfNotClean(FilterPoint.AFTERVIEW)
                return true
            }
        }
    }

    /**
     * Initialize by finding all the DataSourceTransactionManagers in the
     * application context.
     */
    protected void initializeFilters() {
        assert dataSourceTransactionManagers == null
        dataSourceTransactionManagers = []
        PlatformTransactionManager mainTxManager = applicationContext.getBean("transactionManager", PlatformTransactionManager)
        if (mainTxManager) {
            initializeForTransactionManager(mainTxManager)
        } else {
            logger.warn("No transactionManager bean is configured in the applicationContext")
        }
    }

    /**
     * Add txMgr to dataSourceTransactionManagers if it's a
     * DataSourceTransactionManager.  Recursively check if it's a Grails
     * ChainedTransactionManager.
     */
    private void initializeForTransactionManager(PlatformTransactionManager txMgr) {
        if (txMgr instanceof ChainedTransactionManager) {
            ((ChainedTransactionManager) txMgr).transactionManagers.each { PlatformTransactionManager chainee ->
                initializeForTransactionManager(chainee)
            }
        } else if (txMgr instanceof DataSourceTransactionManager) {
            dataSourceTransactionManagers.add((DataSourceTransactionManager) txMgr)
        }
    }

    /**
     * If a dataSource transaction resource is bound to thread, report a
     * detail log error that explains the problem and what to do about it.
     */
    protected void warnIfNotClean(FilterPoint filterPoint) {
        if (isAnyDataSourceBoundToThread()) {
            warnNotClean(filterPoint)
        }
    }

    private void warnNotClean(FilterPoint filterPoint) {
        logger.error(filterPoint == FilterPoint.BEFORE ? ATSTART_NOT_CLEAN_MSG : ATEND_NOT_CLEAN_MSG)
    }

    /**
     * Checks that any DataSource registered in the application context
     * transaction managers is bound as a transaction resource to the
     * thread.
     */
    boolean isAnyDataSourceBoundToThread() {
        return dataSourceTransactionManagers.any { DataSourceTransactionManager txMgr ->
            isDataSourceBoundToThread(txMgr.dataSource)
        }
    }

    /**
     * Checks if a particular DataSource is bound as a transaction resource
     * to the thread.
     */
    boolean isDataSourceBoundToThread(DataSource dataSource) {
        return TransactionSynchronizationManager.hasResource(dataSource)
    }

    static final String ATSTART_NOT_CLEAN_MSG = getNotCleanMsg(FilterPoint.BEFORE)
    static final String ATEND_NOT_CLEAN_MSG = getNotCleanMsg(FilterPoint.AFTERVIEW)

    private static getNotCleanMsg(final FilterPoint filterPoint) {
        String point = (filterPoint == FilterPoint.BEFORE ? "start" : "end")
        return "A transaction resource is still bound to the thread at the ${point} of the web request.  " +
                "This indicates a likely transaction leak bug and problems can be expected.  " +
                "This is most commonly caused by manually getting a connection from the dataSource " +
                "or instantiating a Groovy Sql object with the dataSource in a method where a Spring " +
                "transaction hasn't already been started.  Two possible solutions: " +
                "One, add @Transactional to the method where the Sql object is being instantiated.  " +
                "Two, instantiate the Sql object with dataSourceUnproxied rather than dataSource."
    }
}
