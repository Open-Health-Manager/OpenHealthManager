/*
Copyright 2022 The MITRE Corporation

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */
package org.mitre.healthmanager.dataMgr

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.jpa.starter.Application
import ca.uhn.fhir.rest.client.api.IGenericClient
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum
import org.hl7.fhir.instance.model.api.IBaseBundle
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Parameters
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.StringType
import org.junit.Assert
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.mitre.healthmanager.sphr.ProcessMessageTests
import org.mitre.healthmanager.sphr.stringFromResource
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.web.server.LocalServerPort

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [Application::class],
    properties = [
        "spring.batch.job.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:dbr4",
        "spring.datasource.username=sa",
        "spring.datasource.password=null",
        "spring.datasource.driverClassName=org.h2.Driver",
        "spring.jpa.properties.hibernate.dialect=ca.uhn.fhir.jpa.model.dialect.HapiFhirH2Dialect",
        "hapi.fhir.enable_repository_validating_interceptor=true",
        "hapi.fhir.fhir_version=r4",
    ]
)
class RebuildAccountTests {

    private val ourLog = LoggerFactory.getLogger(ProcessMessageTests::class.java)
    private val ourCtx: FhirContext = FhirContext.forR4()
    init {
        ourCtx.restfulClientFactory.serverValidationMode = ServerValidationModeEnum.NEVER
        ourCtx.restfulClientFactory.socketTimeout = 1200 * 1000
    }

    @LocalServerPort
    private var port = 0

    @Test
    @Order(0)
    fun testPatientOnlyRebuild() {
        val methodName = "testSuccessfulBundleStorage"
        ourLog.info("Entering $methodName()...")
        val testClient: IGenericClient = ourCtx.newRestfulGenericClient("http://localhost:$port/fhir/")

        // file test data
        // has username identifier and first / last name
        val transactionBundle: Bundle = ourCtx.newJsonParser().parseResource<Bundle>(
            Bundle::class.java, stringFromResource("healthmanager/dataMgr/RebuildAccountTests/PatientOnlyBundleTransaction.json")
        )
        testClient.transaction().withBundle(transactionBundle).execute()

        // verify present - first and last name
        val patientResultsBundle = testClient
            .search<IBaseBundle>()
            .forResource(Patient::class.java)
            .where(Patient.IDENTIFIER.exactly().systemAndIdentifier("urn:mitre:healthmanager:account:username", "naccount"))
            .returnBundle(Bundle::class.java)
            .execute()
        Assertions.assertEquals(1, patientResultsBundle.entry.size)
        val patId = patientResultsBundle.entry[0].resource.idElement.idPart
        val patResource = testClient.read().resource(Patient::class.java).withId(patId).encodedJson().execute()
        Assertions.assertEquals("Account", patResource.nameFirstRep.family)
        Assertions.assertEquals("New", patResource.nameFirstRep.givenAsSingleString)

        // trigger rebuild (operation)
        // Create the input parameters to pass to the server
        val inParams = Parameters()
        inParams.addParameter().setName("username").value = StringType("naccount")

        testClient
            .operation()
            .onServer()
            .named("\$rebuild-account")
            .withParameters(inParams)
            .execute()

        // verify updates - no first and last name anymore
        val patResourceRebuilt = testClient.read().resource(Patient::class.java).withId(patId).encodedJson().execute()
        Assertions.assertEquals(0, patResourceRebuilt.name.size)
    }
}