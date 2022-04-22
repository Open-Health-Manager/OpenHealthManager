package org.mitre.healthmanager.dataMgr.resourceTypes

import ca.uhn.fhir.jpa.api.dao.IFhirResourceDaoPatient
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent
import org.hl7.fhir.r4.model.MessageHeader
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Resource

/// default implementation:
/// - if an id is present, check if it exists in the system
/// - otherwise, no match
fun findExistingResource(theResource: Resource, patientInternalId : String) : String? {
    return when (theResource) {
        is Patient -> {
            theResource.findExistingResource(patientInternalId)
        }
        else -> {
            theResource.findExistingResource(patientInternalId)
        }
    }
}

/// default implementation: wrap in a transaction and store
fun doCreate(theResource: Resource, username: String) : BundleEntryComponent? {
    return when (theResource) {
        is Patient -> {
            theResource.doCreate(username)
        }
        else -> {
            theResource.doCreate(username)
        }
    }
}

/// default implementation: wrap in a transaction and store
fun doUpdate(theResource: Resource, id: String, username: String) : BundleEntryComponent?  {
    return when (theResource) {
        is Patient -> {
            theResource.doUpdate(id, username)
        }
        else -> {
            theResource.doUpdate(id, username)
        }
    }
}

fun findUsernameViaLinkedPatient(theResource: Resource, patientDao : IFhirResourceDaoPatient<Patient>) : String? {

    return when (theResource) {
        is Patient -> {
            theResource.findUsernameViaLinkedPatient(patientDao)
        }
        is Bundle -> {
            theResource.findUsernameViaLinkedPatient(patientDao)
        }
        is MessageHeader -> {
            theResource.findUsernameViaLinkedPatient(patientDao)
        }
        else -> {
            theResource.findUsernameViaLinkedPatient(patientDao)
        }
    }
}