package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import org.mitre.healthmanager.dataMgr.org.mitre.healthmanager.dataMgr.AccountProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import javax.servlet.ServletException;

@Import(AppProperties.class)
public class JpaRestfulServer extends BaseJpaRestfulServer {
	@Autowired
	AppProperties appProperties;

	private static final long serialVersionUID = 1L;

	public JpaRestfulServer() {
    super();
  }

  	@Override
  	protected void initialize() throws ServletException {
		super.initialize();
		//IGenericClient client = null; //myDaoRegistry.getSystemDao().getContext().newRestfulGenericClient(appProperties.getServer_address());

		registerProvider(new AccountProvider());

  }

}
