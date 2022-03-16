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
package org.mitre.healthmanager.sphr

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.jpa.starter.Application
import ca.uhn.fhir.rest.client.api.IGenericClient
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum
import org.hl7.fhir.instance.model.api.IBaseBundle
import org.hl7.fhir.r4.model.*
import org.junit.jupiter.api.*
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
class ProcessHealthKitMessageTests {

    private val ourLog = LoggerFactory.getLogger(ProcessMessageTests::class.java)
    private val ourCtx: FhirContext = FhirContext.forR4()
    init {
        ourCtx.restfulClientFactory.serverValidationMode = ServerValidationModeEnum.NEVER
        ourCtx.restfulClientFactory.socketTimeout = 1200 * 1000
    }

    @LocalServerPort
    private var port = 0

    @Test
    fun testAppleHealthKitBundleStorage() {
        val methodName = "testAppleHealthKitBundleStorage"
        ourLog.info("Entering $methodName()...")
        val testClient : IGenericClient = ourCtx.newRestfulGenericClient("http://localhost:$port/fhir/")

        // Submit the bundle
        val messageBundle: Bundle = ourCtx.newJsonParser().parseResource(
            Bundle::class.java, stringFromResource("healthmanager/sphr/ProcessMessageTests/BundleMessage_AppleHealthKit.json")
        )
        val response : Bundle = testClient
            .operation()
            .processMessage()
            .setMessageBundle<Bundle>(messageBundle)
            .synchronous(Bundle::class.java)
            .execute()

        Assertions.assertEquals(1, response.entry.size)
        when (val firstResource = response.entry[0].resource) {
            is MessageHeader -> {
                Assertions.assertEquals(firstResource.response.code, MessageHeader.ResponseType.OK)
            }
            else -> {
                Assertions.fail("response doesn't have a message header")
            }
        }

        Thread.sleep(1000) // give indexing a second to occur

        // find the patient id
        val patientResultsBundle : Bundle = testClient
            .search<IBaseBundle>()
            .forResource(Patient::class.java)
            //.where(Patient.IDENTIFIER.exactly().systemAndIdentifier("urn:mitre:healthmanager:account:username", "test"))
            .returnBundle(Bundle::class.java)
            .execute()

        Assertions.assertEquals(1, patientResultsBundle.entry.size)
        val patientId = when (val firstResource = patientResultsBundle.entry[0].resource) {
            is Patient -> {
                val username = firstResource.identifier.filter { id -> id.system == "urn:mitre:healthmanager:account:username" }
                Assertions.assertEquals(1, username.size)
                Assertions.assertEquals("test", username[0].value)
                firstResource.idElement.idPart
            }
            else -> {
                Assertions.fail("response didn't return a patient")
            }
        }

        // check other resources
        val patientEverythingResult : Parameters = testClient
            .operation()
            .onInstance(IdType("Patient", patientId))
            .named("\$everything")
            .withNoParameters(Parameters::class.java)
            .useHttpGet()
            .execute()
        Assertions.assertEquals(1, patientEverythingResult.parameter.size)
        when (val everythingBundle = patientEverythingResult.parameter[0].resource) {
            is Bundle -> {
                Assertions.assertEquals(15, everythingBundle.entry.size)
            }
            else -> {
                Assertions.fail("\$everything didn't return a bundle")
            }
        }
    }
}