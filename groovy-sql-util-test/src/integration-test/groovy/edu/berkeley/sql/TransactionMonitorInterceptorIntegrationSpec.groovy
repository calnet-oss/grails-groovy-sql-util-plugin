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

import grails.core.GrailsApplication
import grails.test.mixin.integration.Integration
import org.apache.commons.logging.Log
import org.springframework.transaction.support.TransactionSynchronizationManager
import spock.lang.Specification

@Integration
class TransactionMonitorInterceptorIntegrationSpec extends Specification {
    static transactional = false

    // injected
    GrailsApplication grailsApplication

    // Mock logger
    Log mockLog = Mock(Log)

    void setup() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.initSynchronization()
        }

        mockLog.error(_) >> { String msg -> println(msg) }

        // make the filter use our mockLog so we can test when an error is logged
        TransactionMonitorInterceptor tmi = grailsApplication.mainContext.beanFactory.getSingleton(TransactionMonitorInterceptor.DEFAULT_BEAN_NAME)
        tmi.logger = mockLog
    }

    void "test TransactionMonitorInterceptor"() {
        given:
        // should be no transactional resources bound to thread in the case in integration test when transactional = false
        assert !TransactionSynchronizationManager.resourceMap

        when:
        def response = "http://localhost:${serverPort}/test/$action".toURL().text

        then:
        response == action
        // if expectStartErrorMsg or expectEndErrorMsg true, then mockLog.error() should have been called
        (expectStartErrorMsg ? 1 : 0) * mockLog.error(TransactionMonitorInterceptor.ATSTART_NOT_CLEAN_MSG)
        (expectEndErrorMsg ? 1 : 0) * mockLog.error(TransactionMonitorInterceptor.ATEND_NOT_CLEAN_MSG)

        where:
        action            | expectStartErrorMsg | expectEndErrorMsg
        "noTransaction"   | false               | false
        "leakTransaction" | false               | true
    }
}
