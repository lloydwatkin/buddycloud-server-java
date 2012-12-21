package org.buddycloud.channelserver;

import java.util.Properties;

import org.apache.log4j.Logger;
import org.jivesoftware.whack.ExternalComponentManager;
import org.xmpp.component.ComponentException;
import org.logicalcobwebs.proxool.ProxoolException;
import org.logicalcobwebs.proxool.configuration.PropertyConfigurator;

public class XmppComponent {

	private static final String DATABASE_CONFIGURATION_FILE = "db.properties";
	
	private static final Logger logger = Logger.getLogger(XmppComponent.class);
	private String hostname;
	private int socket;
	
	private String channelServer;
	private String anonServer;
	private String topicsServer;
	private String password;
	private Properties conf;
	
	public XmppComponent(Properties conf) {
	    setConf(conf);
		hostname = conf.getProperty("xmpp.host");
		socket = Integer.valueOf(conf.getProperty("xmpp.port"));
		channelServer = conf.getProperty("server.domain.channels");
		password = conf.getProperty("xmpp.secretkey");
		topicsServer = conf.getProperty("server.domain.topics");
		anonServer = conf.getProperty("server.domain.anon");

		try {
			PropertyConfigurator.configure(DATABASE_CONFIGURATION_FILE);
		} catch (ProxoolException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	public void setConf(Properties conf) {
		this.conf = conf;
	}
	
	public void run() throws ComponentException {
		ExternalComponentManager manager = new ExternalComponentManager(
		        hostname, socket);
		ChannelsEngine channelsEngine = new ChannelsEngine(conf);
		manager.setDefaultSecretKey(this.password);
		
		manager.addComponent(channelServer, channelsEngine);
		if (null != topicsServer) {
		    manager.addComponent(topicsServer, channelsEngine);
		}
		if (null != anonServer) {
		    manager.addComponent(anonServer, channelsEngine);
		}
	}
}