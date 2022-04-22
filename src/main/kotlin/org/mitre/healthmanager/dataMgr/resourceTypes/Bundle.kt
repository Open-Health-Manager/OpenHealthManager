package org.mitre.healthmanager.dataMgr.resourceTypes

import ca.uhn.fhir.jpa.api.dao.IFhirResourceDaoPatient
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Patient

// look at individual entries
fun Bundle.findUsernameViaLinkedPatient(patientDao : IFhirResourceDaoPatient<Patient>) : String? {

    for (entry in this.entry) {
        findUsernameViaLinkedPatient(entry.resource, patientDao)?.let { return it }
    }
    return null
}