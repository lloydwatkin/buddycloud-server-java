package org.buddycloud.channelserver;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class Configuration extends Properties
{
	private static final long serialVersionUID = 1L;

	public static final String CONFIGURATION_SERVER_DOMAIN = "server.domain";
	public static final String CONFIGURATION_SERVER_CHANNELS_DOMAIN = "server.domain.channels";
	public static final String CONFIGURATION_SERVER_TOPICS_DOMAIN = "server.domain.topics";
	public static final String CONFIGURATION_SERVER_ANON_DOMAIN = "server.domain.anon";
	
	private static final String CONFIGURATION_FILE = "configuration.properties";
	private static Configuration instance          = null;
	
	private Properties conf;
	
    private Configuration()
    {
    	try {
	        conf = new Properties();
	        conf.load(new FileInputStream(CONFIGURATION_FILE));
    	} catch (Exception e) {
            System.out.println(e.getMessage());
            System.exit(1);
    	}
    }
    
    public static Configuration getInstance() 
    {
    	if (null == instance) {
    		instance = new Configuration();
    	}
    	return instance;
    }
    
    public String getProperty(String key)
    {
    	return conf.getProperty(key);
    }
    
    public String getProperty(String key, String defaultValue)
    {
    	return conf.getProperty(key, defaultValue);
    }
    
    public void load(InputStream inputStream) throws IOException
    {
        conf.load(inputStream);
    }
}