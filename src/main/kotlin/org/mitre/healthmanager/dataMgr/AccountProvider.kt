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
import ca.uhn.fhir.parser.IParser
import ca.uhn.fhir.rest.annotation.Operation
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException
import org.hl7.fhir.r4.model.OperationOutcome
import org.hl7.fhir.r4.model.Parameters
import org.hl7.fhir.r4.model.StringType
import org.mitre.healthmanager.dataMgr.rebuildAccount
import java.io.IOException
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse


class AccountProvider{

    @Operation(name = "\$rebuild-account", manualResponse = true, manualRequest = true)
    @Throws(
        IOException::class
    )
    fun rebuildAccountOperation(theServletRequest: HttpServletRequest, theServletResponse: HttpServletResponse) {

        val reader = theServletRequest.reader
        val data: String = reader.readText()
        reader.close()

        val ctx = FhirContext.forR4()
        val parser: IParser = ctx.newJsonParser()
        val parsedData: Parameters = parser.parseResource(Parameters::class.java, data)

        val username = when (val usernameRaw = parsedData.parameter[0].value) {
            is StringType -> {
                usernameRaw.value
            }
            else -> {
                throw UnprocessableEntityException("\$rebuild-account parameter must be a string")
            }
        }

        val serverAddress = theServletRequest.requestURL.toString().substringBefore("\$")
        rebuildAccount(username, ctx.newRestfulGenericClient(serverAddress))

        theServletResponse.contentType = "application/fhir+json"
        theServletResponse.writer.write(ctx.newJsonParser().encodeResourceToString(getOkOutcome()))
        theServletResponse.writer.close()
    }

    @Operation(name = "\$delete-account", manualResponse = true, manualRequest = true)
    @Throws(
        IOException::class
    )
    fun deleteAccountOperation(theServletRequest: HttpServletRequest, theServletResponse: HttpServletResponse) {

        val reader = theServletRequest.reader
        val data: String = reader.readText()
        reader.close()

        val ctx = FhirContext.forR4()
        val parser: IParser = ctx.newJsonParser()
        val parsedData: Parameters = parser.parseResource(Parameters::class.java, data)

        val username = when (val usernameRaw = parsedData.parameter[0].value) {
            is StringType -> {
                usernameRaw.value
            }
            else -> {
                throw UnprocessableEntityException("\$delete-account parameter must be a string")
            }
        }

        val serverAddress = theServletRequest.requestURL.toString().substringBefore("\$")
        deleteAccount(username, ctx.newRestfulGenericClient(serverAddress))

        theServletResponse.contentType = "application/fhir+json"
        theServletResponse.writer.write(ctx.newJsonParser().encodeResourceToString(getOkOutcome()))
        theServletResponse.writer.close()
    }

    @Operation(name = "\$login", manualResponse = true, manualRequest = true)
    @Throws(
        IOException::class
    )
    fun loginOperation(theServletRequest: HttpServletRequest, theServletResponse: HttpServletResponse) {
        // Example request body ("name" means username):
        // { "resourceType": "Parameters",
        //   "parameter": [{ "name" : "aHealthKit" }]}

        val reader = theServletRequest.reader
        val data: String = reader.readText()
        reader.close()

        val ctx = FhirContext.forR4()
        val parser: IParser = ctx.newJsonParser()
        val parsedData: Parameters = parser.parseResource(Parameters::class.java, data)

        val username = when (val usernameRaw = parsedData.parameter[0].name) {
            is String -> {
                usernameRaw
            }
            else -> {
                throw UnprocessableEntityException("\$login parameter must be a string")
            }
        }

        val serverAddress = theServletRequest.requestURL.toString().substringBefore("\$")
        var jwt = createJWT(username, ctx.newRestfulGenericClient(serverAddress))

        theServletResponse.contentType = "application/fhir+json"
        theServletResponse.writer.write(ctx.newJsonParser().encodeResourceToString(getOkOutcome()))
        theServletResponse.writer.write("Token Created: " + jwt)
        theServletResponse.writer.close()
    }


}

fun getOkOutcome() : OperationOutcome {
    val outcomeOk = OperationOutcome()
    val issueOk = OperationOutcome.OperationOutcomeIssueComponent()
    issueOk.code = OperationOutcome.IssueType.INFORMATIONAL
    issueOk.severity = OperationOutcome.IssueSeverity.INFORMATION
    issueOk.details.text = "All OK"
    outcomeOk.issue.add(issueOk)
    return outcomeOk
}