package org.mitre.healthmanager.sphr

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import ca.uhn.fhir.interceptor.api.Hook
import ca.uhn.fhir.interceptor.api.Interceptor
import ca.uhn.fhir.interceptor.api.Pointcut
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDaoPatient
import ca.uhn.fhir.jpa.dao.TransactionProcessor
import ca.uhn.fhir.model.primitive.IdDt
import ca.uhn.fhir.rest.api.RestOperationTypeEnum
import ca.uhn.fhir.rest.api.server.RequestDetails
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.r4.model.*
import org.mitre.healthmanager.dataMgr.*


@Interceptor
class RequestInterceptor(private val myPatientDaoR4: IFhirResourceDaoPatient<Patient>,
                         private val myBundleDaoR4: IFhirResourceDao<Bundle>,
                         private val myMessageHeaderDaoR4: IFhirResourceDao<MessageHeader>,
                         private val myTransactionProcessor: TransactionProcessor) {

    @Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLED)
    fun reactToRequest(requestDetails: RequestDetails,
                        serveletRequestDetails: ServletRequestDetails,
                        restOperation: RestOperationTypeEnum
    ) {

        when (restOperation) {
            RestOperationTypeEnum.CREATE -> {
                addUpdateOrCreateToPDR(requestDetails, serveletRequestDetails, restOperation)
            }
            RestOperationTypeEnum.UPDATE -> {
                addUpdateOrCreateToPDR(requestDetails, serveletRequestDetails, restOperation)
            }
            RestOperationTypeEnum.TRANSACTION -> {
                val username = getUsernameForRequest(requestDetails, myPatientDaoR4)
                    ?: throw UnprocessableEntityException("could not identify account username for TRANSACTION")
                val patientId = ensureUsername(username, myPatientDaoR4, false)
                val source = getSourceForRequest(serveletRequestDetails)
                // todo: transaction - will need to handle
                throw UnprocessableEntityException("Direct Transactions not supported")
            }
            RestOperationTypeEnum.BATCH -> {
                val username = getUsernameForRequest(requestDetails, myPatientDaoR4)
                    ?: throw UnprocessableEntityException("could not identify account username for BATCH")
                val source = getSourceForRequest(serveletRequestDetails)
                // todo: batch - will need to handle
                throw UnprocessableEntityException("Direct Batches not supported")
            }
            RestOperationTypeEnum.EXTENDED_OPERATION_INSTANCE -> {
                // todo: operation - will need to handle in some cases
            }
            RestOperationTypeEnum.EXTENDED_OPERATION_TYPE -> {
                // todo: operation - will need to handle in some cases
            }
            RestOperationTypeEnum.EXTENDED_OPERATION_SERVER -> {
                // todo: operation - will need to handle in some cases
            }
            RestOperationTypeEnum.DELETE -> {
                throw UnprocessableEntityException("Direct Deletes not supported")
            }
            RestOperationTypeEnum.PATCH -> {
                throw UnprocessableEntityException("Direct Patches not supported")
            }
            else -> {
                // Nothing needed for these
                //    ADD_TAGS("add-tags", false, false, true),
                //    DELETE_TAGS("delete-tags", false, false, true),
                //    GET_TAGS("get-tags", false, true, true),
                //    GET_PAGE("get-page", false, false, false),
                //    GRAPHQL_REQUEST("graphql-request", false, false, false),
                //    HISTORY_INSTANCE("history-instance", false, false, true),
                //    HISTORY_SYSTEM("history-system", true, false, false),
                //    HISTORY_TYPE("history-type", false, true, false),
                //    READ("read", false, false, true),
                //    SEARCH_SYSTEM("search-system", true, false, false),
                //    SEARCH_TYPE("search-type", false, true, false),
                //    VALIDATE("validate", false, true, true),
                //    VREAD("vread", false, false, true),
                //    METADATA("metadata", false, false, false),
                //    META_ADD("$meta-add", false, false, false),
                //    META("$meta", false, false, false),
                //    META_DELETE("$meta-delete", false, false, false),
            }
        }
    }

    @Hook(Pointcut.SERVER_OUTGOING_RESPONSE)
    fun reactToResponse(requestDetails: RequestDetails,
                        serveletRequestDetails: ServletRequestDetails,
                        responseResource: IBaseResource,
                        theRequest: HttpServletRequest,
                        theResponse: HttpServletResponse): Boolean {

        when (requestDetails.restOperationType) {
            RestOperationTypeEnum.CREATE -> {
                updatePDRBundleWithStoredLink(requestDetails, responseResource)
            }
            RestOperationTypeEnum.UPDATE -> {
                updatePDRBundleWithStoredLink(requestDetails, responseResource)
            }
            RestOperationTypeEnum.TRANSACTION -> {
                // todo: transaction - will need to handle
            }
            RestOperationTypeEnum.BATCH -> {
                // todo: batch - will need to handle
            }
            RestOperationTypeEnum.EXTENDED_OPERATION_INSTANCE -> {
                // todo: operation - will need to handle in some cases
            }
            RestOperationTypeEnum.EXTENDED_OPERATION_TYPE -> {
                // todo: operation - will need to handle in some cases
            }
            RestOperationTypeEnum.EXTENDED_OPERATION_SERVER -> {
                // todo: operation - will need to handle in some cases
            }
            RestOperationTypeEnum.DELETE -> {
                throw UnprocessableEntityException("Direct Deletes not supported")
            }
            RestOperationTypeEnum.PATCH -> {
                throw UnprocessableEntityException("Direct Patches not supported")
            }
            else -> {
                // Nothing needed for these
                //    ADD_TAGS("add-tags", false, false, true),
                //    DELETE_TAGS("delete-tags", false, false, true),
                //    GET_TAGS("get-tags", false, true, true),
                //    GET_PAGE("get-page", false, false, false),
                //    GRAPHQL_REQUEST("graphql-request", false, false, false),
                //    HISTORY_INSTANCE("history-instance", false, false, true),
                //    HISTORY_SYSTEM("history-system", true, false, false),
                //    HISTORY_TYPE("history-type", false, true, false),
                //    READ("read", false, false, true),
                //    SEARCH_SYSTEM("search-system", true, false, false),
                //    SEARCH_TYPE("search-type", false, true, false),
                //    VALIDATE("validate", false, true, true),
                //    VREAD("vread", false, false, true),
                //    METADATA("metadata", false, false, false),
                //    META_ADD("$meta-add", false, false, false),
                //    META("$meta", false, false, false),
                //    META_DELETE("$meta-delete", false, false, false),
            }
        }

        return true
    }

    private fun addUpdateOrCreateToPDR(requestDetails: RequestDetails,
                                       serveletRequestDetails: ServletRequestDetails,
                                       restOperation: RestOperationTypeEnum) {
        val theResource : DomainResource =  if (requestDetails.resource is DomainResource) requestDetails.resource as DomainResource else throw InternalErrorException("no DomainResource for create")

        // NOTE on theResource.id
        // - HAPI ensures it MUST be populated if this is a PUT (Update or Create)
        // - HAPI clears it out if this is a POST (Create with system id)
        // therefore, no handling is needed here and we can infer when re-processing PDRs
        // based on the presence of the id field whether we're doing a put or a post

        // todo: special case for patient create. cases include:
        // - create (put) with a specific id
        // - system create turns into a update (put)

        val username = getUsernameForRequest(requestDetails, myPatientDaoR4)
            ?: throw UnprocessableEntityException("could not identify account username for CREATE")
        val patientId = ensureUsername(username, myPatientDaoR4, false)
        val source = getSourceForRequest(serveletRequestDetails)
        val pdrMessageHeader : MessageHeader = findRecentPDRForPatientAndSource(patientId, source, 120, myMessageHeaderDaoR4)
            ?: run {
                // no recent PDR, create one
                val newMessageHeader = generatePDRMessageHeaderObject(username, source)
                val newPDRBundle = generatePDRBundleObject(newMessageHeader)
                val newPDRBundleId = storePDRAsRawBundle(newPDRBundle, myBundleDaoR4)
                val newMessageHeaderId = storePDRMessageHeader(newMessageHeader, patientId, newPDRBundleId, myMessageHeaderDaoR4)
                newPDRBundle.entryFirstRep.link.add(Bundle.BundleLinkComponent().setUrl("MessageHeader/$newMessageHeaderId"))
                updatePDRRawBundle(newPDRBundle, newPDRBundleId, myBundleDaoR4) // links added
                newMessageHeader
            }

        // add resource to PDR Bundle
        val pdrBundleId = getBundleIdFromMessageHeader(pdrMessageHeader)
        val pdrBundle = myBundleDaoR4.read(IdDt(pdrBundleId))
        pdrBundle.entry.add(Bundle.BundleEntryComponent().setResource(theResource.copy()))
        updatePDRRawBundle(pdrBundle, pdrBundleId, myBundleDaoR4)

        // update MessageHeader lastUpdated timestamp
        // todo

        // add bundle id and account username to the created resource
        addPDRLinkExtension(theResource, pdrBundleId)
        addPatientAccountExtension(theResource, username)

        // ready to file as normal - will update bundle link afterwards
    }

    private fun updatePDRBundleWithStoredLink(requestDetails: RequestDetails,
                                              responseResource: IBaseResource) {
        val theResource : DomainResource = if (responseResource is DomainResource) responseResource else throw InternalErrorException("expected domain resource")

        val pdrBundleLinkExtension = getPDRLinkListExtensionFromResource(theResource)
        if (pdrBundleLinkExtension.extension.size == 0) {
            throw UnprocessableEntityException("resource stored without a bundle link")
        }
        val link = theResource.idElement.toString()
        val lastBundleId = when (val extValue = pdrBundleLinkExtension.extension.last().value) {
            is Reference -> {
                extValue.reference
            }
            else -> {
                throw InternalErrorException("bad pdr link extension")
            }

        }
        val storedBundle = myBundleDaoR4.read(IdDt(lastBundleId))
        storedBundle.entry.last().link.add(Bundle.BundleLinkComponent().setUrl(link))
        updatePDRRawBundle(storedBundle, lastBundleId, myBundleDaoR4)
    }

}
