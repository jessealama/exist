package org.exist.http.realm;

import java.security.Principal;

/**
 * @author mdiggory
 */
public interface XmldbPrincipal extends Principal {

	public String getName();
	
	public String getPassword();
	
	public boolean hasRole(String role);

}