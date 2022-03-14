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
import ca.uhn.fhir.jpa.api.dao.DaoRegistry
import ca.uhn.fhir.jpa.starter.AppProperties
import ca.uhn.fhir.rest.client.api.IGenericClient
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException
import org.hl7.fhir.instance.model.api.IBaseBundle
import org.hl7.fhir.r4.model.*
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent
import org.springframework.beans.factory.annotation.Autowired

fun rebuildAccount(username : String, client: IGenericClient) {

    /// use username to find the patient id
    val accountPatientId = getPatientIdForUsername(username, client)
        ?: throw InternalErrorException("Rebuild failed: no patient record for '$username'")

    // Simple initial implementation
    // 1. use $everything to get resources associated with the account
    val everythingBundle = getEverythingForAccount(username, accountPatientId, client)
    // 2. create a transaction that deletes them all except for the patient itself
    val deleteTransactionBundle = getEverythingDeleteTransactionBundle(everythingBundle)
    // 3. submit the bundle as a transaction
    if (deleteTransactionBundle.entry.size > 0) {
        client.transaction().withBundle(deleteTransactionBundle).execute()
    }
    // 3. revert the patient instance back to the skeleton record
    updateAccountToSkeletonPatientInstance(username, accountPatientId, client)
    // 4. reprocess all existing PDRs
    // TODO

}

fun getEverythingForAccount(username: String, patientId: String?, client: IGenericClient) : Bundle {
    val updateId = when {
        (patientId == null) -> {
            getPatientIdForUsername(username, client)
        }
        else -> {
            patientId
        }
    }

    val patientEverythingResult : Parameters = client
        .operation()
        .onInstance(IdType("Patient", updateId))
        .named("\$everything")
        .withNoParameters(Parameters::class.java)
        .useHttpGet()
        .execute()

    if (patientEverythingResult.parameter.size == 0) {
        throw InternalErrorException("\$everything invocation failed for '$username'")
    }

    when (val everythingBundle = patientEverythingResult.parameter[0].resource) {
        is Bundle -> {
            return everythingBundle
        }
        else -> {
            throw InternalErrorException("\$everything didn't return a bundle")
        }
    }

}

fun getEverythingDeleteTransactionBundle(everythingBundle : Bundle, fullRemoval : Boolean = false) : Bundle {

    val transactionBundle = Bundle()
    everythingBundle.entry.forEach { theEntry ->
        when (theEntry.resource) {
            is Patient, is MessageHeader, is Bundle -> {
                if (fullRemoval) {
                    val deleteEntry = BundleEntryComponent()
                    deleteEntry.request.method = Bundle.HTTPVerb.DELETE
                    deleteEntry.request.url =
                        theEntry.resource.resourceType.name + "/" + theEntry.resource.idElement.idPart
                    transactionBundle.entry.add(deleteEntry)
                }
            }
            else -> {
                val deleteEntry = BundleEntryComponent()
                deleteEntry.request.method = Bundle.HTTPVerb.DELETE
                deleteEntry.request.url = theEntry.resource.resourceType.name + "/" + theEntry.resource.idElement.idPart
                transactionBundle.entry.add(deleteEntry)
            }
        }


    }
    return transactionBundle

}



fun getPatientIdForUsername(username: String, client: IGenericClient) : String? {

    // check if username exists already. If not, create skeleton record
    val patientSearchClient: IGenericClient = client
    val patientResultsBundle = patientSearchClient
        .search<IBaseBundle>()
        .forResource(Patient::class.java)
        .where(Patient.IDENTIFIER.exactly().systemAndIdentifier("urn:mitre:healthmanager:account:username", username))
        .returnBundle(Bundle::class.java)
        .execute()

    return when (patientResultsBundle.entry.size) {
        0 -> {
            null
        }
        1 -> {
            patientResultsBundle.entry[0].resource.idElement.idPart
        }
        else -> {
            throw InternalErrorException("multiple patient instances with username '$username'")
        }
    }
}

fun getAccountSkeletonPatientInstance(username: String) : Patient {
    val patientSkeleton = Patient()
    val identifier = patientSkeleton.addIdentifier()
    identifier.value = username
    identifier.system = "urn:mitre:healthmanager:account:username"
    return patientSkeleton
}

fun createAccountSkeletonPatientInstance(username: String, client: IGenericClient) : String {
    val skeleton = getAccountSkeletonPatientInstance(username)

    val createResults = client
        .create()
        .resource(skeleton)
        .prettyPrint()
        .encodedJson()
        .execute()
    return createResults.resource.idElement.idPart

}

fun updateAccountToSkeletonPatientInstance(username: String, patientId : String?, client: IGenericClient) {
    val updateId = when {
        (patientId == null) -> {
            getPatientIdForUsername(username, client)
        }
        else -> {
            patientId
        }
    }

    val skeleton = getAccountSkeletonPatientInstance(username)
    skeleton.id = updateId
    client
        .update()
        .resource(skeleton)
        .prettyPrint()
        .encodedJson()
        .execute()
}

fun ensureUsername(username : String, client: IGenericClient) {

    val patientId = getPatientIdForUsername(username, client)
    if (patientId == null) {
        createAccountSkeletonPatientInstance(username, client)
    }
}