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
import ca.uhn.fhir.jpa.dao.r4.FhirSystemDaoR4
import ca.uhn.fhir.jpa.starter.AppProperties
import ca.uhn.fhir.rest.api.server.RequestDetails
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException
import org.hl7.fhir.instance.model.api.IBaseBundle
import org.hl7.fhir.r4.model.*
import org.springframework.beans.factory.annotation.Autowired
import org.mitre.healthmanager.dataMgr.isPDRMessage
import org.mitre.healthmanager.dataMgr.processPDR


@Autowired
var appProperties: AppProperties? = null

open class ProcessMessage : FhirSystemDaoR4() {

    override fun processMessage(theRequestDetails: RequestDetails, theMessage: IBaseBundle?): IBaseBundle {

        val fhirContext : FhirContext = myDaoRegistry?.systemDao?.context
            ?: throw InternalErrorException("no fhircontext")

        // Validation and initial processing
        // - payload must be a bundle

        if (theMessage !is Bundle) {
            throw UnprocessableEntityException("bundle not provided to \$process-message")
        }
        // - bundle must have type 'message'
        // - bundle first entry must be a MessageHeader entry
        val theHeader = getMessageHeader(theMessage)

        if (isPDRMessage(theHeader) ) {
            processPDR(theHeader, theMessage, fhirContext.newRestfulGenericClient(theRequestDetails.fhirServerBase))
        }
        else {
            throw UnprocessableEntityException("message event not supported")
        }

        // NOTE: this line is the reason the provider doesn't do this itself
        // -- it doesn't know its own address (HapiProperties is JPA server only)
        val serverAddress: String = appProperties?.server_address ?: theRequestDetails.fhirServerBase
        val response = Bundle()
        response.type = Bundle.BundleType.MESSAGE
        val newHeader = MessageHeader()
        newHeader.addDestination().endpoint = theHeader.source.endpoint
        newHeader.source = MessageHeader.MessageSourceComponent()
            .setEndpoint("$serverAddress\$process-message")
        newHeader.response = MessageHeader.MessageHeaderResponseComponent()
            .setCode(MessageHeader.ResponseType.OK)
        response.addEntry().resource = newHeader

        return response
    }
}

fun getMessageHeader(theMessage : Bundle) : MessageHeader {

    if (theMessage.type != Bundle.BundleType.MESSAGE) {
        throw UnprocessableEntityException("\$process-message bundle must have type 'message'")
    }

    if (theMessage.entry.size > 0) {
        when (val firstEntry = theMessage.entry[0].resource) {
            is MessageHeader -> {
                return firstEntry
            }
            else -> {
                throw UnprocessableEntityException("First entry of the message Bundle must be a MessageHeader instance")
            }
        }
    }
    else {
        throw UnprocessableEntityException("message Bundle must have at least a MessageHeader entry")
    }

}



