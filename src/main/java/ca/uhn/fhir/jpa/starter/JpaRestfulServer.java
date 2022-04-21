package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDaoPatient;
import ca.uhn.fhir.jpa.dao.TransactionProcessor;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.interceptor.ExceptionHandlingInterceptor;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.MessageHeader;
import org.hl7.fhir.r4.model.Patient;
import org.mitre.healthmanager.dataMgr.AccountInterceptor;
import org.mitre.healthmanager.dataMgr.AccountProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;

import javax.servlet.ServletException;

@Import(AppProperties.class)
public class JpaRestfulServer extends BaseJpaRestfulServer {
	@Autowired
	AppProperties appProperties;

	@Autowired
	@Qualifier("myPatientDaoR4")
	protected IFhirResourceDaoPatient<Patient> myPatientDao;
	@Autowired
	@Qualifier("myBundleDaoR4")
	protected IFhirResourceDao<Bundle> myBundleDao;
	@Autowired
	@Qualifier("myMessageHeaderDaoR4")
	protected IFhirResourceDao<MessageHeader> myMessageHeaderDao;
	@Autowired
	private TransactionProcessor myTransactionProcessor;

	private static final long serialVersionUID = 1L;

	public JpaRestfulServer() {
    super();
  }

  	@Override
  	protected void initialize() throws ServletException {
		super.initialize();

		registerProvider(new AccountProvider(myPatientDao, myBundleDao, myMessageHeaderDao, myTransactionProcessor));
		registerInterceptor(new AccountInterceptor());

		ExceptionHandlingInterceptor interceptor = new ExceptionHandlingInterceptor();
		registerInterceptor(interceptor);

		// Return the stack trace to the client for the following exception types
		//interceptor.setReturnStackTracesForExceptionTypes(InternalErrorException.class, NullPointerException.class);

  }

}
