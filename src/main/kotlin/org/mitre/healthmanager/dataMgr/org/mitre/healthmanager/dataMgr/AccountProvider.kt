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
package org.mitre.healthmanager.dataMgr.org.mitre.healthmanager.dataMgr

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.parser.IParser
import ca.uhn.fhir.rest.annotation.Operation
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException
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


    }


}