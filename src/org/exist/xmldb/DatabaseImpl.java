/*
 *  eXist Open Source Native XML Database
 *  Copyright (C) 2001 Wolfgang M. Meier
 *  wolfgang@exist-db.org
 *  http://exist.sourceforge.net
 *
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public License
 *  as published by the Free Software Foundation; er version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 *  
 *  $Id:
 */
package org.exist.xmldb;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashMap;
import java.util.StringTokenizer;
import java.util.regex.Pattern;

import org.apache.xmlrpc.XmlRpc;
import org.apache.xmlrpc.XmlRpcClient;
import org.exist.EXistException;
import org.exist.security.User;
import org.exist.storage.BrokerPool;
import org.exist.util.Configuration;
import org.xmldb.api.base.Collection;
import org.xmldb.api.base.Database;
import org.xmldb.api.base.ErrorCodes;
import org.xmldb.api.base.XMLDBException;

/**
 * The XMLDB driver class for eXist. This driver manages two different
 * internal implementations. The first communicates with a remote 
 * database using the XMLRPC protocol. The second has direct access
 * to an embedded database instance running in the same virtual machine.
 * The driver chooses an implementation depending on the XML:DB URI passed
 * to getCollection().
 * 
 * When running in embedded mode, the driver can create a new database
 * instance if none is available yet. It will do so if the property
 * "create-database" is set to "true" or if there is a system property
 * "exist.initdb" with value "true".
 * 
 * You may optionally provide the location of an alternate configuration
 * file through the "configuration" property. The driver is also able to
 * address different database instances - which may have been installed at
 * different places.
 * 
 * @author Wolfgang Meier
 */
public class DatabaseImpl implements Database {

	//TODO : discuss about other possible values
	protected final static String LOCAL_HOSTNAME = "";    
    
    protected final static int UNKNOWN_CONNECTION = -1;
    protected final static int LOCAL_CONNECTION = 0;
    protected final static int REMOTE_CONNECTION = 1;
    
    protected boolean autoCreate = false;
    protected String configuration = null;
   
    private HashMap rpcClients = new HashMap();
    protected ShutdownListener shutdown = null;
	protected int mode = UNKNOWN_CONNECTION;
	
	//TODO : for compiance with xmldb API. Usage to be clarified !!!
	String currentInstanceName = BrokerPool.DEFAULT_INSTANCE;
	
    public DatabaseImpl() {
        try {
            XmlRpc.setEncoding( "UTF-8" );
        } catch ( Exception e ) {
        }
    	String initdb = System.getProperty( "exist.initdb" );
    	if(initdb != null)
    		autoCreate = initdb.equalsIgnoreCase("true");
    }

    public static Collection readCollection(String c, XmlRpcClient rpcClient) 
    		throws XMLDBException {
    	//TODO : refactor
        StringTokenizer tok = new StringTokenizer( c, "/" );
        String temp = tok.nextToken();
        if(temp.equals("db"))
        	temp = '/' + temp;
        Collection current =
            new RemoteCollection( rpcClient, null, temp );
        while ( current != null && tok.hasMoreTokens() ) {
            temp = tok.nextToken();
            current =
                current.getChildCollection( ( (RemoteCollection) current ).getPath() + '/' + temp );
        }
        return current;
    }

    public boolean acceptsURI(String xmldbURI) throws XMLDBException {
    	XmldbURI uri;
    	try {    		
    		uri = new XmldbURI(XmldbURI.XMLDB_URI_PREFIX + xmldbURI);
    	} catch (Exception e) {    		
    		throw new XMLDBException(ErrorCodes.INVALID_DATABASE, "xmldb URI is not well formed:" + xmldbURI);   
    	}    	
        return (uri.getURI() != null);
    }

    /**
     *  In embedded mode: configure the database instance
     *
     *@exception  XMLDBException  Description of the Exception
     */    
    private void configure(String InstanceName) throws XMLDBException {
        String home, file = "conf.xml";
        if(configuration == null) {
        	home = findExistHomeFromProperties();
        } else {
        	File f = new File(configuration);
        	if (!f.isAbsolute())
        		f = new File(new File(findExistHomeFromProperties()), configuration).getAbsoluteFile();
    		file = f.getName();
			home = f.getParentFile().getPath();
        }
		System.out.println("configuring '" + InstanceName + "' using " + home + File.pathSeparator + file);
        try {
            Configuration config = new Configuration(file, home);
            BrokerPool.configure(InstanceName, 1, 5, config);
            if (shutdown != null)
            	BrokerPool.getInstance(InstanceName).registerShutdownListener(shutdown);
        } catch (Exception e ) {
            throw new XMLDBException(ErrorCodes.VENDOR_ERROR, "configuration error", e );
        }
    }

	/**
	 * @return Exist Home dir. From system Properties
	 */
	private String findExistHomeFromProperties() {
		String home;
		home = System.getProperty("exist.home");
		if (home == null)
			home = System.getProperty("user.dir");
		return home;
	}

    public Collection getCollection(String xmldbURI, String user, String password)
    		throws XMLDBException {  
    	XmldbURI uri = null;
    	try {
    		uri = new XmldbURI(XmldbURI.XMLDB_URI_PREFIX + xmldbURI);
    	} catch (Exception e) {    		
    		throw new XMLDBException(ErrorCodes.INVALID_DATABASE, "xmldb URI is not well formed:" + xmldbURI);   
    	}
    	
    	if ("direct access".equals(uri.getApiName()))    		
        	return getLocalCollection(uri.getInstanceName(), user, password, uri.getCollectionName());
    	else if ("xmlrpc".equals(uri.getApiName())){
    		URL url = null;
    		try {
    			url = new URL("http", uri.getHost(), uri.getPort(), uri.getRemoteContext());
    		} catch (MalformedURLException e) {
        		//Should never happen
        		throw new XMLDBException(ErrorCodes.INVALID_DATABASE, "xmldb URL is not well formed:" + xmldbURI);   
        	}
    		return getRemoteCollectionFromXMLRPC(url, uri.getInstanceName(), user, password, uri.getCollectionName());
    	}
    	else 
    		throw new XMLDBException(ErrorCodes.INVALID_DATABASE, "xmldb URL is not well formed:" + xmldbURI);  
    }    

    /**
     * @param url
     * @param instanceName
     * @param user
     * @param password
     * @param c
     * @return
     * @throws XMLDBException
     */
    private Collection getRemoteCollectionFromXMLRPC(URL url, String instanceName, 
    		String user, String password, String c) throws XMLDBException {        
        mode = REMOTE_CONNECTION;                   
        if (user == null) {
            user = "guest";
            password = "guest";
        } 
        if(password == null)
        	password = "";            
        XmlRpcClient rpcClient = getRpcClient(user, password, url);
        return readCollection(c, rpcClient);
    }

    /**
     * @param instanceName
     * @param user
     * @param password
     * @param c
     * @return
     * @throws XMLDBException
     */
    private Collection getLocalCollection(String instanceName, String user, String password, String c) 
    	throws XMLDBException {
        Collection current;
        mode = LOCAL_CONNECTION;
        // use local database instance
        if (!BrokerPool.isConfigured(instanceName)) {
            if (autoCreate)
                configure(instanceName);
            else
                throw new XMLDBException(ErrorCodes.COLLECTION_CLOSED, "Local database server is not running");
        }
        BrokerPool pool;
        try {
            pool = BrokerPool.getInstance(instanceName);
        } catch (EXistException e) {
            throw new XMLDBException( ErrorCodes.VENDOR_ERROR, "db not correctly initialized", e);
        }
        User u = getUser(user, password, pool);
        try {
            current = new LocalCollection(u, pool, c);
            return (current != null) ? current : null;
        } catch (XMLDBException e) {
            switch (e.errorCode) {
                case ErrorCodes.NO_SUCH_RESOURCE:
                case ErrorCodes.NO_SUCH_COLLECTION:
                case ErrorCodes.INVALID_COLLECTION:
                case ErrorCodes.INVALID_RESOURCE:
                    return null;
                default:
                    throw e;
            }
        }
    }

    /**
     * @param user
     * @param pool
     * @return the User object corresponding to the username in <code>user</code>
     * @throws XMLDBException
     */
    private User getUser(String user, String password, BrokerPool pool) throws XMLDBException {
        if (user == null) {
            user = "guest";
            password = "guest";
        }
    	User u = pool.getSecurityManager().getUser(user);
        if (u == null) {
        	throw new XMLDBException( ErrorCodes.PERMISSION_DENIED, "user '" + user + "' does not exist");
        }
        if (!u.validate(password) ) {
        	throw new XMLDBException( ErrorCodes.PERMISSION_DENIED, "invalid password for user '" + user + "'");
        }
        return u;
    }

    /**
     * RpcClients are cached by address+user. The password is transparently changed.
     * @param user
     * @param password
     * @param address
     * @throws XMLDBException
     */
    private XmlRpcClient getRpcClient(String user, String password, URL url) throws XMLDBException {
        String key = user + "@" + url.toString();
        XmlRpcClient client = (XmlRpcClient) rpcClients.get(key);
        if (client == null) {         
           client = new XmlRpcClient(url);
           if (client != null) {
        	   client.setBasicAuthentication(user, password);           
        	   rpcClients.put(key, client);
           }
        }            
        return client;
    }

    public String getConformanceLevel() throws XMLDBException {
        return "0";
    }

    //TODO : clarify usage !!!
    public String getName() throws XMLDBException {
        return currentInstanceName;
    }
	
    //TODO : clarify usage !!!
	public String[] getNames() throws XMLDBException {
		return new String[] {currentInstanceName};
	}
	
	/**
	 * Register a ShutdownListener for the current database instance. The ShutdownListener is called
	 * after the database has shut down. You have to register a listener before any calls to getCollection().
	 * 
	 * @param listener
	 * @throws XMLDBException
	 */
	public void setDatabaseShutdownListener(ShutdownListener listener) throws XMLDBException {
		shutdown = listener;
	}
	
    public String getProperty(String property) throws XMLDBException {
        if (property.equals("create-database"))
            return Boolean.valueOf(autoCreate).toString();
        //TODO : rename ?
        if (property.equals("database-id")) 
//        	TODO : consider multivalued property
        	return currentInstanceName;
        if (property.equals("configuration"))
        	return configuration;
        return null;
    }

    public void setProperty(String property, String value) throws XMLDBException {
        if (property.equals("create-database"))
            autoCreate = value.equals("true");
        //TODO : rename ?
		if (property.equals("database-id")) 
//			TODO : consider multivalued property
			currentInstanceName = value;
		if (property.equals("configuration"))
			configuration = value;
    }
    
    public class XmldbURI {
    	
    	//Should be provided by org.xmldb.api package !!! 
    	public static final String XMLDB_URI_PREFIX = "xmldb:";
    	
    	private URI uri;
    	private String instanceName;
    	private String host;
    	private int port;    	
    	private String collectionName;
    	private String apiName;
    	private String remoteContext;
    	
    	//Note that we construct URIs starting with XmldbURI.XMLDB_URI_PREFIX 
    	public XmldbURI(String xmldbURI) throws URISyntaxException, XMLDBException {        	
        	try {
        		if (!xmldbURI.startsWith(XMLDB_URI_PREFIX))
        			throw new XMLDBException(ErrorCodes.INVALID_DATABASE, "This is not an xmldb URI :" + xmldbURI);
        		uri = new URI(xmldbURI.substring(XMLDB_URI_PREFIX.length()));
        		instanceName = uri.getScheme();    		 
            	if ("".equals(instanceName))
            		throw new XMLDBException(ErrorCodes.INVALID_DATABASE, "No instance name for xmldb URI :" + xmldbURI);
            	//TODO : consider using LOCAL_HOSTNAME
            	host = uri.getHost();
            	port = uri.getPort();
            	//Trim trailing slash if necessary
            	Pattern p = Pattern.compile(".+/$");            	
            	String normalizedPath = p.split(uri.getPath())[0];              	
            	//TODO : use named constants            	
            	if (normalizedPath.startsWith("/webdav")) {
            		apiName = "webdav";
            		remoteContext = "/webdav";
            		collectionName = normalizedPath.substring("/webdav".length());
            	}
            	else if (normalizedPath.startsWith("/xmlrpc")) {
            		apiName = "xmlrpc";
            		remoteContext = "/xmlrpc";
            		collectionName = normalizedPath.substring("/xmlrpc".length());
            	}  
            	else {
            		apiName = "direct access";
            		remoteContext = "";
            		collectionName =  normalizedPath; 
            		if (port > -1)
            			throw new XMLDBException(ErrorCodes.INVALID_DATABASE, "Local xmldb URI should not provide a port:" + xmldbURI);
            	} 
            	//TODO in the future : check that collectionName starts with DBBroker.ROOT_COLLECTION ?
        	} catch (URISyntaxException e) {
            	uri = null;
            	throw e;        	
	    	} catch (XMLDBException e) {
	        	uri = null;
	        	throw e;
	    	}
    	}
    	
    	//TODO : prefefined URIs as satic classes...
    	
    	public URI getURI() { return uri; }
    	public String getInstanceName() { return (uri == null) ? null : instanceName; }
    	public String getHost() { return  (uri == null) ? null : host; }
    	public int getPort() { return  (uri == null) ? -1 : port; }
    	public String getCollectionName() { return (uri == null) ? null : collectionName; }
    	public String getApiName() { return (uri == null) ? null : apiName; }
    	public String getRemoteContext() { return (uri == null) ? null : remoteContext; }
  	
    }

}

