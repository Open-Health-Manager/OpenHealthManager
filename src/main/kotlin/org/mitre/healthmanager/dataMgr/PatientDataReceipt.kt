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

import ca.uhn.fhir.rest.client.api.IGenericClient
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException
import org.hl7.fhir.r4.model.*
import org.mitre.healthmanager.sphr.getMessageHeader
import java.util.*

fun isPDRMessage(header : MessageHeader) : Boolean {
    /// check header event
    return when (val headerEvent = header.event) {
        is UriType -> {
            headerEvent.valueAsString == "urn:mitre:healthmanager:pdr"
        }
        else -> {
            false
        }
    }
}

fun processPDR(header : MessageHeader, theMessage : Bundle, client : IGenericClient) {

    // validation: must have at least two entries (header plus content)
    // validation: username extension must be present
    validatePDR(theMessage)
    val username = getUsernameFromPDRHeader(header)

    // identify internal account representation (create if needed
    val patientInternalId = ensureUsername(username, client)

    // store
    // 1. the Bundle in its raw form
    // 2. the MessageHeader linking the Bundle instance to the account Patient instance
    // 3. the non-MessageHeader contents of the Bundle individually
    val bundleInternalId = storePDRAsRawBundle(theMessage, client)
    val messageHeaderInternalId = storePDRMessageHeader(header, patientInternalId, bundleInternalId, client)
    storeIndividualPDREntries(theMessage, patientInternalId, client, header)

}

fun storePDRAsRawBundle(theMessage: Bundle, client: IGenericClient) : String {
    // store the bundle as a bundle
    val createBundleResults = client
        .create()
        .resource(theMessage)
        .prettyPrint()
        .encodedJson()
        .execute()
    // TODO: error handling
    return createBundleResults.id.idPart
}

fun storePDRMessageHeader(theHeader : MessageHeader, patientInternalId : String, bundleInternalId : String, client: IGenericClient) : String {

    // Store the MessageHeader (theHeader) as its own instance with the following changes
    // The focus list of the message header will be updated to contain only references to
    // - The bundle instance containing the message contents (id via results.id.idPart)
    // - The patient instance representing the account (id via patientInternalId)
    theHeader.focus.clear() // clear existing references for now to avoid need to adjust them later
    theHeader.focus.add(0, Reference("Bundle/$bundleInternalId"))
    theHeader.focus.add(1, Reference("Patient/$patientInternalId"))
    val createMessageHeaderResults = client
        .create()
        .resource(theHeader)
        .prettyPrint()
        .encodedJson()
        .execute()
    // TODO: error handling
    return createMessageHeaderResults.id.idPart
}

fun storeIndividualPDREntries(theMessage: Bundle, patientInternalId: String, client: IGenericClient, theHeader : MessageHeader?) {
    val headerToCheck = theHeader ?: getMessageHeader(theMessage)

    if (headerToCheck.source.endpoint == "urn:apple:health-kit") {
        // specific temporary logic to handle apple health kit issues, including
        // 1. no patient entry, which is needed to make the references work
        // 2. links to encounter records, but encounters aren't present
        fixAppleHealthKitBundle(theMessage, patientInternalId)
    }

    // store individual entries
    // take the theMessage and make the following changes/checks:
    // - type is transaction
    // - remove MessageHeader entry
    // - make sure request details for Patient is put with the ID from step 2 (patientInternalId)
    // - make sure request details for all other types is post with whatever that type is
    // - TODO: check that there is a patient entry (which is either an already existing patient or a new patient the ID needs to be updated by using the messageHeader focus list from above)
    theMessage.type = Bundle.BundleType.TRANSACTION
    var indexToRemove: Int? = null
    var patientFound = false
    for ((index, entry) in theMessage.entry.withIndex()) {

        /// make sure a fullUrl is present
        if ((entry.fullUrl == null) || (entry.fullUrl == "")) {
            val entryId = entry.resource.idElement.idPart
            entry.fullUrl = when {
                entryId == null -> {
                    "" // can't refer to this resource
                }
                isGUID(entryId) -> {
                    "urn:uuid:$entryId"
                }
                entryId != "" -> {
                    "${entry.resource.resourceType}/$entryId"
                }
                else -> {
                    ""
                }
            }
        }

        // update / add request details
        when (entry.resource.resourceType) {
            ResourceType.MessageHeader -> {
                indexToRemove = index
            }
            ResourceType.Patient -> {
                if (patientFound) {
                    throw UnprocessableEntityException("PDR cannot have more than 1 patient instance")
                }
                else {
                    patientFound = true
                }

                // update this patient record, linkages will be updated by bundle processing
                entry.request.method = Bundle.HTTPVerb.PUT
                entry.request.url = "Patient/" + patientInternalId
            }
            else -> {
                // create for all else
                entry.request.method = Bundle.HTTPVerb.POST
                entry.request.url = entry.resource.resourceType.toString()
            }
        }
    }
    // remove the MessageHeader entry
    if (indexToRemove is Int) {
        theMessage.entry.removeAt(indexToRemove)
    }

    // process the transaction
    client
        .transaction()
        .withBundle(theMessage)
        .execute()
    /// TODO: error handling

}

fun validatePDR(theMessage : Bundle) {
    if (theMessage.entry.size < 2) {
        throw UnprocessableEntityException("Patient Data Receipt must have at least one additional entry beside the MessageHeader")
    }
}

/// Returns the username associated with this messageheader
fun getUsernameFromPDRHeader (header : MessageHeader) : String {

    // get username from extension
    if (header.hasExtension("https://github.com/Open-Health-Manager/patient-data-receipt-ig/StructureDefinition/AccountExtension")) {
        val usernameExtension = header.getExtensionByUrl("https://github.com/Open-Health-Manager/patient-data-receipt-ig/StructureDefinition/AccountExtension")
        when (val usernameExtValue = usernameExtension.value) {
            is StringType -> {
                return usernameExtValue.value
            }
            else -> {
                throw UnprocessableEntityException("invalid username extension in pdr message header")
            }
        }
    }
    else {
        throw UnprocessableEntityException("no username found in pdr message header")
    }

}

fun isGUID(theId : String?) : Boolean {
    return try {
        UUID.fromString(theId)
        true
    } catch (exception: IllegalArgumentException) {
        false
    }
}