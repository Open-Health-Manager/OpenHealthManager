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
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.mitre.healthmanager.dataMgr.pdrAccountExtension
import org.mitre.healthmanager.dataMgr.pdrLinkListExtensionURL
import org.mitre.healthmanager.searchForPatientByUsername
import org.mitre.healthmanager.stringFromResource
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
class DirectCreateTests {

    private val ourLog = LoggerFactory.getLogger(DirectCreateTests::class.java)
    private val ourCtx: FhirContext = FhirContext.forR4()
    init {
        ourCtx.restfulClientFactory.serverValidationMode = ServerValidationModeEnum.NEVER
        ourCtx.restfulClientFactory.socketTimeout = 1200 * 1000
    }

    @LocalServerPort
    private var port = 0

    @Test
    fun testTwoWrites() {
        val methodName = "testTwoWrites"
        ourLog.info("Entering $methodName()...")
        val testClient : IGenericClient = ourCtx.newRestfulGenericClient("http://localhost:$port/fhir/")
        val testUsername = "directWriteTwo"

        // make sure patient doesn't exist
        val results = testClient
            .search<IBaseBundle>()
            .forResource(Patient::class.java)
            .where(Patient.IDENTIFIER.exactly().systemAndIdentifier("urn:mitre:healthmanager:account:username", testUsername))
            .returnBundle(Bundle::class.java)
            .execute()
        Assertions.assertEquals(0, results?.entry?.size!!)

        // Submit the bundle
        val messageBundle: Bundle = ourCtx.newJsonParser().parseResource(
            Bundle::class.java, stringFromResource("healthmanager/sphr/DirectCreateTests/PDR_CreatePatient.json")
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

        // make sure patient does exist
        val patientId: String? = searchForPatientByUsername(testUsername, testClient, 120)
        Assertions.assertNotNull(patientId)

        val observation: Observation = ourCtx.newJsonParser().parseResource(
            Observation::class.java, stringFromResource("healthmanager/sphr/DirectCreateTests/Observation_Write.json")
        )
        observation.subject = Reference("Patient/$patientId")

        /// create twice in quick succession
        val responseOne = testClient
            .create()
            .resource(observation)
            .prettyPrint()
            .encodedJson()
            .execute()
            .resource
        val responseTwo = testClient
            .create()
            .resource(observation)
            .prettyPrint()
            .encodedJson()
            .execute()
            .resource
        val observationOne = if (responseOne is Observation) responseOne else null
        val observationTwo = if (responseTwo is Observation) responseTwo else null

        // checks
        // - each observation has a different id, username extension in meta, and list of bundles
        // - both bundles are the same
        Assertions.assertNotNull(observationOne)
        Assertions.assertNotNull(observationTwo)
        Assertions.assertTrue(observationOne!!.meta.hasExtension(pdrAccountExtension))
        Assertions.assertEquals(testUsername, observationOne.meta.getExtensionByUrl(pdrAccountExtension).value.toString())
        Assertions.assertTrue(observationTwo!!.meta.hasExtension(pdrAccountExtension))
        Assertions.assertEquals(testUsername, observationTwo.meta.getExtensionByUrl(pdrAccountExtension).value.toString())
        Assertions.assertTrue(observationOne.meta.hasExtension(pdrLinkListExtensionURL))
        Assertions.assertTrue(observationTwo.meta.hasExtension(pdrLinkListExtensionURL))
        val pdrLinkListOne = observationOne.meta.getExtensionByUrl(pdrLinkListExtensionURL)
        Assertions.assertEquals(1, pdrLinkListOne.extension.size)
        Assertions.assertTrue(pdrLinkListOne.extension[0].value is Reference)
        val pdrLinkListTwo = observationTwo.meta.getExtensionByUrl(pdrLinkListExtensionURL)
        Assertions.assertEquals(1, pdrLinkListTwo.extension.size)
        Assertions.assertTrue(pdrLinkListTwo.extension[0].value is Reference)
        val bundleRefOne = pdrLinkListOne.extension[0].value as Reference
        val bundleRefTwo = pdrLinkListTwo.extension[0].value as Reference
        Assertions.assertEquals(bundleRefOne.reference, bundleRefTwo.reference)

        // get the Bundle associated with the stored observations
        val bundleId = bundleRefOne.reference.substringAfter("/")
        val pdrBundle = testClient
            .read()
            .resource(Bundle::class.java)
            .withId(bundleId)
            .execute()
        Assertions.assertEquals(3, pdrBundle.entry.size)
        for ((index, entry) in pdrBundle.entry.withIndex()) {
            when {
                (index == 0) -> {
                    Assertions.assertTrue(entry.resource is MessageHeader)
                }
                (index == 1) -> {
                    Assertions.assertTrue(entry.resource is Observation)
                    Assertions.assertEquals(responseOne.idElement.toString(), entry.linkFirstRep.url)
                }
                (index == 2) -> {
                    Assertions.assertTrue(entry.resource is Observation)
                    Assertions.assertEquals(responseTwo.idElement.toString(), entry.linkFirstRep.url)
                }
            }
        }

        // get the MessageHeader
        val messageHeader = testClient
            .read()
            .resource(MessageHeader::class.java)
            .withId(pdrBundle.entry[0].linkFirstRep.url.split("/")[1])
            .execute()
        Assertions.assertEquals(2, messageHeader.focus.size)
        var sawPatient = false
        var sawBundle = false
        messageHeader.focus.forEach {
            val typeAndId = it.reference.split("/")
            if (typeAndId[0] == "Patient") {
                sawPatient = true
                Assertions.assertEquals(patientId, typeAndId[1])
            }
            if (typeAndId[0] == "Bundle") {
                sawBundle = true
                Assertions.assertEquals(bundleId, typeAndId[1])
            }
        }
        Assertions.assertTrue(sawPatient)
        Assertions.assertTrue(sawBundle)


    }
}

