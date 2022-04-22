package org.mitre.healthmanager.dataMgr.resourceTypes

import ca.uhn.fhir.jpa.api.dao.IFhirResourceDaoPatient
import ca.uhn.fhir.jpa.dao.TransactionProcessor
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Resource
import org.mitre.healthmanager.dataMgr.isGUID

/// default implementation:
/// - if an id is present, check if it exists in the system
/// - otherwise, no match
fun Resource.findExistingResource(patientInternalId : String) : String? {
    return if (this.idElement.idPart != null) {
        // todo: implement the check - need the Dao
        null
    }
    else {
        null
    }
}

/// default implementation: wrap in a transaction and store
fun Resource.doCreate( username: String) : Bundle.BundleEntryComponent? {

    val entry = Bundle.BundleEntryComponent()
    val resourceType = this.resourceType.name
    entry.resource = this

    val resourceId = this.idElement.idPart
    entry.fullUrl = when {
        resourceId == null -> {
            "" // can't refer to this resource
        }
        isGUID(resourceId) -> {
            "urn:uuid:$resourceId"
        }
        resourceId != "" -> {
            "${entry.resource.resourceType}/$resourceId"
        }
        else -> {
            "" // can't refer to this resource
        }
    }

    if ((resourceId == null) || (resourceId == "")) {
        entry.request.url = resourceType
        entry.request.method = Bundle.HTTPVerb.POST
    }
    else {
        when (resourceId.toIntOrNull()) {
            null -> {
                // Non-empty, not an int, so can store it
                // todo: should this be an option (will update if already exists)?
                entry.request.url = "$resourceType/$resourceId"
                entry.request.method = Bundle.HTTPVerb.PUT

            }
            else -> {
                // non-empty int, HAPI won't create
                entry.request.url = resourceType
                entry.request.method = Bundle.HTTPVerb.POST
            }
        }
    }
    return entry
}

/// default implementation: wrap in a transaction and store
fun Resource.doUpdate(id: String, username: String) : Bundle.BundleEntryComponent?  {

    val entry = Bundle.BundleEntryComponent()
    val resourceId = this.idElement.idPart
    entry.fullUrl = when {
        resourceId == null -> {
            "" // can't refer to this resource
        }
        isGUID(resourceId) -> {
            "urn:uuid:$resourceId"
        }
        resourceId != "" -> {
            "${entry.resource.resourceType}/$resourceId"
        }
        else -> {
            "" // can't refer to this resource
        }
    }

    val resourceType = this.resourceType.name
    entry.resource = this
    entry.request.url = "$resourceType/$id"
    entry.request.method = Bundle.HTTPVerb.PUT

    return entry
}

/// base implementation fails
fun Resource.findUsernameViaLinkedPatient(patientDao : IFhirResourceDaoPatient<Patient>) : String? {

    return null
}