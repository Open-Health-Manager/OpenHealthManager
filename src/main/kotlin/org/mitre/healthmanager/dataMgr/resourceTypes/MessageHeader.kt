package org.mitre.healthmanager.dataMgr.resourceTypes

import ca.uhn.fhir.jpa.api.dao.IFhirResourceDaoPatient
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException
import org.hl7.fhir.r4.model.MessageHeader
import org.hl7.fhir.r4.model.Patient
import org.mitre.healthmanager.dataMgr.getUsernameFromPDRHeader

// look at individual entries
fun MessageHeader.findUsernameViaLinkedPatient(patientDao : IFhirResourceDaoPatient<Patient>) : String? {

    try {
        return getUsernameFromPDRHeader(this)
    } catch (e: UnprocessableEntityException) {
        // not a PDR, continue
    }
    return null
}