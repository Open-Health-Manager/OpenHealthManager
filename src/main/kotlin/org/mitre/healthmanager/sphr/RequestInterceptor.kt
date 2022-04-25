package org.mitre.healthmanager.sphr

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.interceptor.api.Hook
import ca.uhn.fhir.interceptor.api.Interceptor
import ca.uhn.fhir.interceptor.api.Pointcut
import ca.uhn.fhir.jpa.api.dao.DaoRegistry
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDaoPatient
import ca.uhn.fhir.jpa.dao.TransactionProcessor
import ca.uhn.fhir.model.primitive.IdDt
import ca.uhn.fhir.rest.api.RequestTypeEnum
import ca.uhn.fhir.rest.api.RestOperationTypeEnum
import ca.uhn.fhir.rest.api.server.RequestDetails
import ca.uhn.fhir.rest.server.RestfulServerUtils
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.r4.model.*
import org.mitre.healthmanager.dataMgr.*
import org.mitre.healthmanager.dataMgr.resourceTypes.isSharedResource
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse


@Interceptor
class RequestInterceptor(private val myPatientDaoR4: IFhirResourceDaoPatient<Patient>,
                         private val myBundleDaoR4: IFhirResourceDao<Bundle>,
                         private val myMessageHeaderDaoR4: IFhirResourceDao<MessageHeader>,
                         private val myTransactionProcessor: TransactionProcessor,
                         private val myDaoRegistry: DaoRegistry
) {

    @Hook(Pointcut.SERVER_INCOMING_REQUEST_POST_PROCESSED)
    fun incomingRequestPostProcessed(requestDetails: RequestDetails,
                                     serveletRequestDetails: ServletRequestDetails,
                                     theRequest: HttpServletRequest,
                                     theResponse: HttpServletResponse): Boolean {

        // handle patient creates
        if ((requestDetails.resourceName == "Patient") && ((requestDetails.requestType == RequestTypeEnum.POST) || (requestDetails.requestType == RequestTypeEnum.PUT))){

            // parse patient data since we'll handle it here
            val data: String = theRequest.reader.readText()
            theRequest.reader.close()
            val ctx = FhirContext.forR4()
            val parser = ctx.newJsonParser()
            val theResource = parser.parseResource(Patient::class.java, data)
            requestDetails.resource = theResource  // will be replaced later, but helpful for calls in this method

            // check if this username exists yet
            val username = getUsernameForRequest(requestDetails, myPatientDaoR4)
                ?: throw UnprocessableEntityException("could not identify account username for Patient CREATE/UPDATE")
            getPatientIdForUsername(username, myPatientDaoR4)?.let { patientInternalId ->
                // update to an existing username
                // allowed (currently) only if this is a PUT for the id of the existing instance
                if (requestDetails.requestType != RequestTypeEnum.PUT) {
                    // POST used
                    throw UnprocessableEntityException("patient record for account already exists")
                }
                else if (theResource.id.substringAfter("/") != patientInternalId) {
                    // Different Id
                    throw UnprocessableEntityException("patient record for account already exists")
                }

                // let the update proceed as usual
                return true
            }
            ?: run {
                // handle the create here since it doesn't appear that we can alter the original request
                // including all the PDR / MessageHeader stuff as well

                // 0. if id specified, check that it isn't already being used for another username
                if (theResource.id != null) {
                    val existingUsername = try {
                        getUsernameFromPatient(myPatientDaoR4.read(theResource.idElement))
                    }
                    catch (e : Exception) {
                        /// doesn't exist, ok to continue
                        null
                    }
                    if ((existingUsername != null) && (existingUsername != username)) {
                        throw UnprocessableEntityException("Cannot use PUT to change a username")
                    }
                }

                // 1. Create Bundle so that we can add a link to the Patient
                val source = getSourceForRequest(serveletRequestDetails)
                val newMessageHeader = generatePDRMessageHeaderObject(username, source)
                val newPDRBundle = generatePDRBundleObject(newMessageHeader)
                newPDRBundle.entry.add(Bundle.BundleEntryComponent().setResource(theResource.copy()))
                val newPDRBundleId = storePDRAsRawBundle(newPDRBundle, myBundleDaoR4)

                // 2. Update the patient resource with the right extensions
                addPDRLinkExtension(theResource, newPDRBundleId)
                addPatientAccountExtension(theResource, username)

                // 3. Create the patient
                val createOutcome = if (theResource.id == null) {
                    myPatientDaoR4.create(theResource)
                }
                else {
                    myPatientDaoR4.update(theResource)
                }
                val patientId = createOutcome.resource.idElement.idPart

                // 4. Create the MessageHeader with the right foci
                val newMessageHeaderId = storePDRMessageHeader(newMessageHeader, patientId, newPDRBundleId, myMessageHeaderDaoR4)

                // 5. Update the Bundle with the links for the patient and the messageheader
                newPDRBundle.entryFirstRep.link.add(Bundle.BundleLinkComponent().setUrl("MessageHeader/$newMessageHeaderId"))
                newPDRBundle.entry[1].link.add(Bundle.BundleLinkComponent().setUrl(createOutcome.resource.idElement.toString()))
                updatePDRRawBundle(newPDRBundle, newPDRBundleId, myBundleDaoR4) // links added

                // 6. Update the Response with the patient creation details
                val response = requestDetails.response.streamResponseAsResource(
                    createOutcome.resource,
                    RestfulServerUtils.prettyPrintResponse(requestDetails.server, requestDetails),
                    RestfulServerUtils.determineSummaryMode(requestDetails),
                    201, // created
                    null,
                    requestDetails.isRespondGzip,
                    true
                )
                // todo: not returning the resource - figure out why....

                return false
            }
        }
        else if ((requestDetails.resourceName == "Bundle") && (requestDetails.requestType == RequestTypeEnum.DELETE)) {
            // check that this is actually a PDR
            val targetBundle = myBundleDaoR4.read(requestDetails.id)
            if ((targetBundle.type == Bundle.BundleType.MESSAGE) &&
                (targetBundle.entry[0] != null) &&
                (targetBundle.entry[0].resource is MessageHeader) &&
                ((targetBundle.entry[0].resource as MessageHeader).eventUriType.equals(pdrEvent))
            ) {
                // delete PDR logic

                val updateTxBundle = Bundle()
                updateTxBundle.type = Bundle.BundleType.TRANSACTION

                // add entry to delete the bundle
                updateTxBundle.addEntry(Bundle.BundleEntryComponent()
                    .setRequest(Bundle.BundleEntryRequestComponent()
                        .setMethod(Bundle.HTTPVerb.DELETE)
                        .setUrl("Bundle/" + targetBundle.idElement.idPart)))

                for ((index, entry) in targetBundle.entry.withIndex()) {
                    if (index == 0) {
                        // already know this is the message header - just delete it
                        updateTxBundle.addEntry(Bundle.BundleEntryComponent()
                            .setRequest(Bundle.BundleEntryRequestComponent()
                                .setMethod(Bundle.HTTPVerb.DELETE)
                                .setUrl("MessageHeader/" + entry.linkFirstRep.url.split("/")[1])))
                    }
                    else if ((entry.link.size == 0) || ((entry.resource != null) && (isSharedResource(entry.resource)))) {
                        // skip - do not delete shared resources
                        // NOTE: means that for now updates to shared resources are sticky!
                        // therefore, overwrite to fix rather than rollback
                    }
                    else {
                        // check the current version - need the resource-specific Dao
                        val locationSplit = if (entry.hasLink()) {
                            entry.linkFirstRep.url.split("/")
                        }
                        else {
                            throw InternalErrorException("missing link in PDR")
                        }
                        val resourceType = locationSplit[0]
                        val resourceInternalId = locationSplit[1]
                        val resourceDao = myDaoRegistry.getResourceDao(resourceType)
                            ?: throw InternalErrorException("failed to find the resource Dao for resource type '$resourceType'")
                        val resourceInstance = resourceDao.read(IdType(resourceType, resourceInternalId))
                            ?: throw InternalErrorException("failed to read resource '$resourceInternalId' of type '$resourceType'")
                        val currentVersion = resourceInstance.meta.versionId
                        val bundleVersion = locationSplit[3]
                        if (bundleVersion == currentVersion) {
                            // look for the most recent existing pdr in the pdr list
                            if (resourceInstance is Resource) {

                                val latestPDR : Bundle? = findLatestPDRBundle(resourceInstance, targetBundle.idElement.idPart)

                                if (latestPDR == null) {

                                    if (resourceType == "Patient") {
                                        // revert to skeleton
                                        val username = getUsernameFromPDRHeader(targetBundle.entry[0].resource as MessageHeader)
                                        updateTxBundle.addEntry(
                                            Bundle.BundleEntryComponent()
                                                .setRequest(
                                                    Bundle.BundleEntryRequestComponent()
                                                        .setMethod(Bundle.HTTPVerb.PUT)
                                                        .setUrl("$resourceType/$resourceInternalId")
                                                )
                                                .setResource(getAccountSkeletonPatientInstance(username))
                                        )
                                    }
                                    else {
                                        // delete this instance
                                        updateTxBundle.addEntry(
                                            Bundle.BundleEntryComponent()
                                                .setRequest(
                                                    Bundle.BundleEntryRequestComponent()
                                                        .setMethod(Bundle.HTTPVerb.DELETE)
                                                        .setUrl("$resourceType/$resourceInternalId")
                                                )
                                        )
                                    }
                                }
                                else {
                                    // find the version - need to loop over the whole pdr
                                    // todo: include the entry index in the extension to optimize
                                    val targetVersion = getResourceVersionFromPDR(latestPDR, resourceType, resourceInternalId)
                                        ?: throw InternalErrorException("No version for resource found in PDR")

                                    // update to this version
                                    val priorInstanceVersion = resourceDao.read(IdDt("$resourceType/$resourceInternalId/_history/$targetVersion"))
                                        ?: throw InternalErrorException("target version read failed")
                                    if (priorInstanceVersion is Resource) {

                                        updateTxBundle.addEntry(
                                            Bundle.BundleEntryComponent()
                                                .setRequest(
                                                    Bundle.BundleEntryRequestComponent()
                                                        .setMethod(Bundle.HTTPVerb.PUT)
                                                        .setUrl("$resourceType/$resourceInternalId")
                                                )
                                                .setResource(priorInstanceVersion)
                                        )
                                    }
                                    else {
                                        throw InternalErrorException("target version read failed - not a Resource")
                                    }
                                }
                            }
                            else {
                                throw InternalErrorException("Expected Resource to be an actual resource")
                            }
                        } // bundle not responsible for the current version, so no update needed

                    }
                }

                // process the transaction
                myTransactionProcessor.transaction(null, updateTxBundle, false)

                // setup the response
                //todo: return a operation outcome like standard HAPI FHIR
                theResponse.status = 204 // no content returned, but successful

                return false

            }
        }
        return true
    }

    private fun findLatestPDRBundle(theResource : Resource, deletedPDRBundleId : String) :Bundle? {
        val pdrListExtension = theResource.meta.getExtensionByUrl(pdrLinkListExtensionURL)
            ?: throw InternalErrorException("resource missing a pdr list")
        val pdrLinks = pdrListExtension.getExtensionsByUrl(pdrLinkExtensionURL)
        for (linkExtension in pdrLinks.reversed()) {
            val pdrBundleId = when (val linkValue = linkExtension.value) {
                is Reference -> {
                    linkValue.reference.split("/")[1]
                }
                else -> {
                    throw InternalErrorException("bad pdr link extension - value not a reference")
                }
            }
            // skip the one that is being deleted
            if (pdrBundleId == deletedPDRBundleId) continue

            // get the Bundle so that we can get the version link
            try {
                myBundleDaoR4.read(IdDt(pdrBundleId))?.let { bundleInstance -> return bundleInstance }
            } catch (e : Exception) {
                // keep looking
            }
        }

        return null
    }

    private fun getResourceVersionFromPDR(pdrBundle : Bundle, resourceType: String, resourceId: String) : String? {
        for (entry in pdrBundle.entry) {
            if (entry.link.size > 0) {
                val locationSplit = entry.linkFirstRep.url.split("/")
                if ((resourceType == locationSplit[0]) && (resourceId == locationSplit[1])) {
                    return locationSplit[3]
                }
            }
        }

        return null
    }

    @Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLED)
    fun reactToRequest(requestDetails: RequestDetails,
                        serveletRequestDetails: ServletRequestDetails,
                        restOperation: RestOperationTypeEnum
    ) {

        if (requestDetails.userData["BaseStorageDao.processingSubRequest"] != null) {
            return
        }

        when (restOperation) {
            RestOperationTypeEnum.CREATE -> {
                addUpdateOrCreateToPDR(requestDetails, serveletRequestDetails, restOperation)
            }
            RestOperationTypeEnum.UPDATE -> {
                addUpdateOrCreateToPDR(requestDetails, serveletRequestDetails, restOperation)
            }
            RestOperationTypeEnum.TRANSACTION -> {
                // todo: transaction - will need to handle
                "test"
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
                //throw UnprocessableEntityException("Direct Deletes not supported")
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

        if (requestDetails.userData["BaseStorageDao.processingSubRequest"] != null) {
            return true
        }

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
                //throw UnprocessableEntityException("Direct Deletes not supported")
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
        val theResource : DomainResource = if (requestDetails.resource is DomainResource) requestDetails.resource as DomainResource
            else throw InternalErrorException("no DomainResource for $restOperation")

        // NOTE on theResource.id
        // - HAPI ensures it MUST be populated if this is a PUT (Update or Create)
        // - HAPI clears it out if this is a POST (Create with system id)
        // therefore, no handling is needed here and we can infer when re-processing PDRs
        // based on the presence of the id field whether we're doing a put or a post
        // NOTE - use of the provided id is convenient, but may not be feasible long-term

        if ( !isSharedResource(theResource) ) {

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

            // make sure that we are starting with the latest pdr list
            // this is managed by the system and should not be provided
            if (theResource.meta.hasExtension(pdrLinkListExtensionURL)) {
                theResource.meta.removeExtension(pdrLinkListExtensionURL)
            }
            if (theResource.hasId()) {
                val resourceType = theResource.resourceType.toString()
                val resourceInternalId = theResource.idElement.idPart
                val resourceDao = myDaoRegistry.getResourceDao(resourceType)
                    ?: throw InternalErrorException("failed to find the resource Dao for resource type '$resourceType'")
                val existingInstance = try {
                    resourceDao.read(IdType(resourceType, resourceInternalId))
                } catch (exception : Exception) {
                    null
                }
                if ((existingInstance is Resource) && (existingInstance.meta.hasExtension(pdrLinkListExtensionURL))) {
                    theResource.meta.extension.add(existingInstance.meta.getExtensionByUrl(pdrLinkListExtensionURL))
                }
            }
            // add bundle id and account username to the created resource
            addPDRLinkExtension(theResource, pdrBundleId)
            addPatientAccountExtension(theResource, username)

            // ready to file as normal - will update bundle link afterwards
        }
    }

    private fun updatePDRBundleWithStoredLink(requestDetails: RequestDetails,
                                              responseResource: IBaseResource) {
        val theResource : DomainResource = if (responseResource is DomainResource) responseResource else throw InternalErrorException("expected domain resource")
        if ( !isSharedResource(theResource) ) {
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

}