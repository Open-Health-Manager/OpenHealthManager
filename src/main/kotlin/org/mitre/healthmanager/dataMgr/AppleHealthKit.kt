package org.mitre.healthmanager.dataMgr

import org.hl7.fhir.r4.model.*

fun fixAppleHealthKitBundle(theMessage : Bundle, internalPatientId : String) {
    var messagePatientId : String? = null

    theMessage.entry.forEach { entry ->
        when (val resource = entry.resource) {
            is Observation -> {

                // replace patient reference with internal reference
                resource.subject.reference = "Patient/$internalPatientId"
                /*
                val patientReference = resource.subject.reference

                val referencedPatientId = patientReference.substringAfter("/")
                if (messagePatientId == null) {
                    messagePatientId = referencedPatientId
                }
                else if (messagePatientId != referencedPatientId) {
                    throw UnprocessableEntityException("Health kit: multiple referenced patients provided, only one allowed")
                }

                 */
                // remove encounter link
                resource.encounter = null

            }
            is Procedure -> {
                // replace patient reference with internal reference
                resource.subject.reference = "Patient/$internalPatientId"
                // remove encounter link
                resource.encounter = null
            }
            is Condition -> {
                // replace patient reference with internal reference
                // NOTE: in DSTU-2 it is patient instead of subject, so probably can't get conditions currently
                resource.subject.reference = "Patient/$internalPatientId"
                resource.asserter = null
            }
            is AllergyIntolerance -> {
                // replace patient reference with internal reference
                resource.patient.reference = "Patient/$internalPatientId"
            }
            else -> {
                // do nothing
            }
        }



    }
}