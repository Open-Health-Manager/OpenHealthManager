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

import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDaoPatient
import ca.uhn.fhir.jpa.dao.TransactionProcessor
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap
import ca.uhn.fhir.model.primitive.IdDt
import ca.uhn.fhir.rest.api.server.IBundleProvider
import ca.uhn.fhir.rest.param.ReferenceParam
import ca.uhn.fhir.rest.param.TokenParam
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.r4.model.*
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent
import javax.servlet.http.HttpServletRequest

fun rebuildAccount(username : String, patientDao : IFhirResourceDaoPatient<Patient>, bundleDao : IFhirResourceDao<Bundle>, messageHeaderDao : IFhirResourceDao<MessageHeader>, txProcessor: TransactionProcessor, originalRequest: HttpServletRequest) {

    if (username == "") {
        throw InternalErrorException("Rebuild failed: no patient record for '$username'")
    }
    /// use username to find the patient id
    val accountPatientId = getPatientIdForUsername(username, patientDao)
        ?: throw InternalErrorException("Rebuild failed: no patient record for '$username'")

    // Simple initial implementation
    // 1. use $everything to get resources associated with the account
    val everythingBundle = getEverythingForAccount(username, accountPatientId, patientDao, originalRequest)
    // 2. create a transaction that deletes them all except for the patient itself
    val deleteTransactionBundle = getEverythingDeleteTransactionBundle(everythingBundle)
    // 3. include reversion of the patient instance back to the skeleton record
    deleteTransactionBundle.entry.add(getSkeletonPatientInstanceUpdateEntry(username, accountPatientId, patientDao))
    // 4. submit the bundle as a transaction
    val deleteOutcome = txProcessor.transaction(null, deleteTransactionBundle, false)
    /// TODO: error handling

    // 5. reprocess all existing PDRs
    val bundleIdList = getPDRBundleIdListForPatient(accountPatientId, messageHeaderDao)
    bundleIdList.forEach { bundleId ->
        val theMessage = bundleDao.read(IdDt("Bundle/$bundleId"))
        storeIndividualPDREntries(theMessage, accountPatientId, txProcessor, null, username)
    }
}

fun deleteAccount(username : String, patientDao : IFhirResourceDaoPatient<Patient>, txProcessor: TransactionProcessor, originalRequest: HttpServletRequest) {

    /// use username to find the patient id
    val accountPatientId = getPatientIdForUsername(username, patientDao)
        ?: throw InternalErrorException("Delete failed: no patient record for '$username'")

    // Simple initial implementation
    // 1. use $everything to get resources associated with the account
    val everythingBundle = getEverythingForAccount(username, accountPatientId, patientDao, originalRequest)
    // 2. create a transaction that deletes them all except for the patient itself
    val deleteTransactionBundle = getEverythingDeleteTransactionBundle(everythingBundle, true)
    // 3. submit the bundle as a transaction
    if (deleteTransactionBundle.entry.size > 0) {
        val deleteOutcome = txProcessor.transaction(null, deleteTransactionBundle, false)
        /// TODO: error handling
    }
}

fun createAccount(username : String, patientDao : IFhirResourceDaoPatient<Patient>) {

    getPatientIdForUsername(username, patientDao)?.let {
        // already exists
        throw InternalErrorException("Create failed: account already exists for '$username'")
    } ?: run {
        createAccountSkeletonPatientInstance(username, patientDao)
    }

}

fun getEverythingForAccount(username: String, patientId: String?, patientDao : IFhirResourceDaoPatient<Patient> , originalRequest : HttpServletRequest) : IBundleProvider {
    val updateId = when {
        (patientId == null) -> {
            getPatientIdForUsername(username, patientDao)
        }
        else -> {
            patientId
        }
    }

    val requestDetails = ca.uhn.fhir.rest.server.servlet.ServletRequestDetails()
    requestDetails.servletRequest = originalRequest
    val everythingFromDao = patientDao.patientInstanceEverything(originalRequest, IdDt("Patient/$updateId"), null, null, null, null, null, null, null, requestDetails)

    if (everythingFromDao.isEmpty) {
        throw InternalErrorException("\$everything invocation failed for '$username'")
    }

    return everythingFromDao

}

fun getEverythingDeleteTransactionBundle(everythingBundle : IBundleProvider, fullRemoval : Boolean = false) : Bundle {

    val transactionBundle = Bundle()
    everythingBundle.allResources.forEach { theEntry ->
        if ( ((theEntry.idElement.resourceType == "Patient") || (theEntry.idElement.resourceType == "MessageHeader") || (theEntry.idElement.resourceType == "Bundle"))) {
            if (fullRemoval) {
                val deleteEntry = BundleEntryComponent()
                deleteEntry.request.method = Bundle.HTTPVerb.DELETE
                deleteEntry.request.url =
                    theEntry.idElement.resourceType + "/" + theEntry.idElement.idPart
                transactionBundle.entry.add(deleteEntry)
            }
        }
        else {
            val deleteEntry = BundleEntryComponent()
            deleteEntry.request.method = Bundle.HTTPVerb.DELETE
            deleteEntry.request.url = theEntry.idElement.resourceType + "/" + theEntry.idElement.idPart
            transactionBundle.entry.add(deleteEntry)
        }


    }
    return transactionBundle

}

fun internalPatientSearchByUsername(username: String, patientDao : IFhirResourceDaoPatient<Patient>) : Patient? {
    val idParam = TokenParam("urn:mitre:healthmanager:account:username", username)
    val searchParameterMap = SearchParameterMap()
    searchParameterMap.add(Patient.SP_IDENTIFIER, idParam)
    searchParameterMap.isLoadSynchronous = true /// disable cache since we may have just created
    val searchResults = patientDao.search(searchParameterMap)
    val patientResultList: List<IBaseResource> = searchResults.allResources
    return when (patientResultList.size) {
        0 -> {
            null
        }
        1 -> {
            when (val resource = patientResultList[0]) {
                is Patient -> { return resource}
                else -> { throw InternalErrorException("internal search returned a non-patient resource")}
            }
        }
        else -> {
            throw InternalErrorException("multiple patient instances with username '$username'")
        }
    }
}

fun getPatientIdForUsername(username: String, patientDao : IFhirResourceDaoPatient<Patient>) : String? {

    // check if username exists already. If not, create skeleton record
    val patientInstance : Patient? = internalPatientSearchByUsername(username, patientDao)

    return patientInstance?.idElement?.idPart
}

fun getUsernameFromPatient(patient : Patient) : String? {
    patient.identifier.forEach {
        if (it.system == "urn:mitre:healthmanager:account:username") {
            return it.value
        }
    }
    return null
}

fun addUsernameToPatient(patient: Patient, username: String) {
    val identifier = Identifier()
    identifier.value = username
    identifier.system = "urn:mitre:healthmanager:account:username"
    patient.identifier.add(0, identifier)
}

fun getAccountSkeletonPatientInstance(username: String) : Patient {
    val patientSkeleton = Patient()
    addUsernameToPatient(patientSkeleton, username)
    return patientSkeleton
}

fun createAccountSkeletonPatientInstance(username: String, patientDao : IFhirResourceDaoPatient<Patient>) : String {
    val skeleton = getAccountSkeletonPatientInstance(username)
    val outcome = patientDao.create(skeleton)
    return outcome.resource.idElement.idPart
}

fun getSkeletonPatientInstanceUpdateEntry(username: String, patientId : String?, patientDao : IFhirResourceDaoPatient<Patient> ) : BundleEntryComponent {
    val updateId = when {
        (patientId == null) -> {
            getPatientIdForUsername(username, patientDao)
        }
        else -> {
            patientId
        }
    }

    val entry = BundleEntryComponent()
    entry.resource = getAccountSkeletonPatientInstance(username)
    entry.request.url = "Patient/$updateId"
    entry.request.method = Bundle.HTTPVerb.PUT
    return entry
}

fun ensureUsername(username : String, patientDao : IFhirResourceDaoPatient<Patient>) : String {

    return getPatientIdForUsername(username, patientDao) ?: createAccountSkeletonPatientInstance(username, patientDao)
}

fun getPDRBundleIdListForPatient(patientId: String, messageHeaderDao : IFhirResourceDao<MessageHeader>) : List<String> {

    val searchParameterMap = SearchParameterMap()
    searchParameterMap.add(MessageHeader.SP_FOCUS, ReferenceParam(IdDt("Patient/$patientId")))
    searchParameterMap.isLoadSynchronous = true /// disable cache since we may have just created
    val searchResults = messageHeaderDao.search(searchParameterMap)
    val messageHeaderResultList: List<IBaseResource> = searchResults.allResources

    val bundleIdList = mutableListOf<String>()
    messageHeaderResultList.forEach { header ->
        when (header) {
            is MessageHeader -> {
                header.focus.forEach { reference ->
                    when (reference.referenceElement.resourceType) {
                        "Bundle" -> {
                            bundleIdList.add(0, reference.referenceElement.idPart)
                        }
                    }
                }
            }
            else -> {
                // do nothing
            }
        }
    }

    return bundleIdList

}