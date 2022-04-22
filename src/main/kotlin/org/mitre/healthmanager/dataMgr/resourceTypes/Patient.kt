package org.mitre.healthmanager.dataMgr.resourceTypes

import ca.uhn.fhir.jpa.api.dao.IFhirResourceDaoPatient
import ca.uhn.fhir.jpa.dao.TransactionProcessor
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Resource
import org.mitre.healthmanager.dataMgr.addUsernameToPatient
import org.mitre.healthmanager.dataMgr.getUsernameFromPatient

/// for patients, get the account username
fun Patient.findExistingResource(patientInternalId: String) : String? {
    return patientInternalId
}

/// make sure the patient has the username
fun Patient.doCreate(username: String) : Bundle.BundleEntryComponent? {
    getUsernameFromPatient(this)?.let {
        if (it != username) {
            throw UnprocessableEntityException("cannot change username associated with a patient")
        }
    }?: run {
        addUsernameToPatient(this, username)
    }

    return (this as Resource).doCreate(
        username
    )
}

/// make sure the patient has the username
fun Patient.doUpdate(id: String, username: String) : Bundle.BundleEntryComponent? {
    getUsernameFromPatient(this)?.let {
        if (it != username) {
            throw UnprocessableEntityException("cannot change username associated with a patient")
        }
    }?: run {
        addUsernameToPatient(this, username)
    }

    return (this as Resource).doUpdate(
        id,
        username
    )
}

// we have the patient, look for the username identifier
fun Patient.findUsernameViaLinkedPatient(patientDao : IFhirResourceDaoPatient<Patient>) : String? {

    return getUsernameFromPatient(this)
}