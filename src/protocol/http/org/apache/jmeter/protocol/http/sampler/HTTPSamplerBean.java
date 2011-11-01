/*
 * ====================================================================
 * The Apache Software License, Version 1.1
 *
 * Copyright (c) 2001-2003 The Apache Software Foundation.  All rights
 * reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in
 * the documentation and/or other materials provided with the
 * distribution.
 *
 * 3. The end-user documentation included with the redistribution,
 * if any, must include the following acknowledgment:
 * "This product includes software developed by the
 * Apache Software Foundation (http://www.apache.org/)."
 * Alternately, this acknowledgment may appear in the software itself,
 * if and wherever such third-party acknowledgments normally appear.
 *
 * 4. The names "Apache" and "Apache Software Foundation" and
 * "Apache JMeter" must not be used to endorse or promote products
 * derived from this software without prior written permission. For
 * written permission, please contact apache@apache.org.
 *
 * 5. Products derived from this software may not be called "Apache",
 * "Apache JMeter", nor may "Apache" appear in their name, without
 * prior written permission of the Apache Software Foundation.
 *
 * THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED.  IN NO EVENT SHALL THE APACHE SOFTWARE FOUNDATION OR
 * ITS CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF
 * USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 */
package org.apache.jmeter.protocol.http.sampler;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;

import java.net.BindException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;

import java.util.Iterator;
import java.util.zip.GZIPInputStream;

import org.apache.jmeter.config.Argument;
import org.apache.jmeter.config.Arguments;

import org.apache.jmeter.protocol.http.control.AuthManager;
import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.parser.HTMLParseException;
import org.apache.jmeter.protocol.http.parser.HTMLParser;
import org.apache.jmeter.protocol.http.util.HTTPArgument;

import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;

import org.apache.jmeter.testbeans.TestBean;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;

import org.apache.jmeter.threads.JMeterContextService;

import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.util.SSLManager;

import org.apache.jorphan.logging.LoggingManager;
import org.apache.jorphan.util.JOrphanUtils;

import org.apache.log.Logger;

import org.apache.oro.text.PatternCacheLRU;
import org.apache.oro.text.regex.Perl5Compiler;
import org.apache.oro.text.regex.Perl5Matcher;
import org.apache.oro.text.regex.StringSubstitution;
import org.apache.oro.text.regex.Substitution;
import org.apache.oro.text.regex.Util;

/**
 * A sampler which understands all the parts necessary to read statistics about
 * HTTP requests, including cookies and authentication.
 *
 * @author    Michael Stover
 * @version   $Revision$ Last updated $Date$
 */
public class HTTPSamplerBean extends TestBean implements Sampler
{
	transient private static Logger log= LoggingManager.getLoggerForClass();

    private static final int DEFAULT_HTTPS_PORT = 443;
    private static final int DEFAULT_HTTP_PORT = 80;

    public final static String HEADERS= "headers";
    public final static String HEADER= "header";
    public final static String DEFAULT_PROTOCOL= "http";
    public final static String POST= "POST";
    public final static String GET= "GET";

    /** A number to indicate that the port has not been set.  **/
    public static final int UNSPECIFIED_PORT= 0;
    private static final int MAX_REDIRECTS= 5; // As recommended by RFC 2068
    private static final int MAX_FRAME_DEPTH= 5;
    private static final int MAX_CONN_RETRIES = 10; // Maximum connection retries
    
    protected static String encoding= "iso-8859-1";
    private static final PostWriter postWriter= new PostWriter();

	protected final static String NON_HTTP_RESPONSE_CODE=
		"Non HTTP response code";
	protected final static String NON_HTTP_RESPONSE_MESSAGE=
		"Non HTTP response message";

    static {// TODO - document what this is doing and why
        System.setProperty(
            "java.protocol.handler.pkgs",
            JMeterUtils.getPropDefault(
                "ssl.pkgs",
                "com.sun.net.ssl.internal.www.protocol"));
        System.setProperty("javax.net.ssl.debug", "all");
    }

    private static PatternCacheLRU patternCache=
        new PatternCacheLRU(1000, new Perl5Compiler());

    private static ThreadLocal localMatcher= new ThreadLocal()
    {
        protected synchronized Object initialValue()
        {
            return new Perl5Matcher();
        }
    };

    private static Substitution spaceSub= new StringSubstitution("%20");

    /* Should we delegate redirects to the URLConnection implementation?
     * This can be useful with alternate URLConnection implementations.
     * 
     * Defaults to false, to maintain backward compatibility. 
     */
    private static boolean delegateRedirects=
        JMeterUtils
            .getJMeterProperties()
            .getProperty("HTTPSampler.delegateRedirects", "false")
            .equalsIgnoreCase("true");

	/**
	 * Constructor for the HTTPSampler object.
	 */
	public HTTPSamplerBean()
	{
		setArguments(new Arguments());
	}

	public HTTPSamplerBean(URL u)
	{
		setMethod(GET);
		setDomain(u.getHost());
		setPath(u.getPath());
		setPort(u.getPort());
		setProtocol(u.getProtocol());
		parseArguments(u.getQuery());
		setFollowRedirects(true);
		setUseKeepAlive(true);
		setArguments(new Arguments());
	}


    /*
     * Public properties:
     */
    private String fileField= ""; // TODO: is this valid? Choose a sensible default or check it's non-null.
    private File filename= null;
    private String protocol= "http";
    private String path= "";
    private String encodedPath= "";
    private boolean followRedirects= true;
    private String Method= "GET";
    private boolean useKeepAlive= false;
    private int port= UNSPECIFIED_PORT;
    private String domain= ""; // TODO: choose a sensible default or check it's non-null.
    private Arguments arguments= null;
    private String mimeType= ""; // TODO: is this valid? Choose a sensible one or check it's non-null.
    private boolean imageParser= false;

    /*
     * Non-public properties:
     */    
    private AuthManager authManager= null;
    private HeaderManager headerManager= null;
    private CookieManager cookieManager= null;

	public void addArgument(String name, String value)
	{
		this.getArguments().addArgument(new HTTPArgument(name, value));
	}

	public void addArgument(String name, String value, String metadata)
	{
		this.getArguments().addArgument(new HTTPArgument(name, value, metadata));
	}

    public void addEncodedArgument(String name, String value, String metaData)
    {
        log.debug(
            "adding argument: name: "
                + name
                + " value: "
                + value
                + " metaData: "
                + metaData);

        HTTPArgument arg= new HTTPArgument(name, value, metaData, true);

        if (arg.getName().equals(arg.getEncodedName())
            && arg.getValue().equals(arg.getEncodedValue()))
        {
            arg.setAlwaysEncoded(false);
        }
        this.getArguments().addArgument(arg);
    }

    /**
     * Do a sampling and return its results.
     *
     * @param e  <code>Entry</code> to be sampled
     * @return   results of the sampling
     */
    public SampleResult sample(Entry e)
    {
        prepare(); // TestBeans must for the time being do this. Awful... TODO: fix.
        
        try
        {
            SampleResult res= sample(getUrl(), getMethod(), false, 0);
            res.setSampleLabel(getName());
            return res;
        }
        catch (MalformedURLException mue)
        {
            return errorResult(mue, getName(), 0);
        }
    }

    /**
     * Obtain a result that will help inform the user that an error has occured
     * during sampling, and how long it took to detect the error.
     * 
     * @param e Exception representing the error.
     * @param data a piece of data associated to the error (e.g. URL)
     * @param time time spent detecting the error (0 for client-only issues)
     * @return a sampling result useful to inform the user about the exception.
     */
    private HTTPSampleResult errorResult(Throwable e, String data, long time)
    {
        HTTPSampleResult res= new HTTPSampleResult(time);
        res.setSampleLabel("Error");
        res.setSamplerData(data);
        res.setDataType(HTTPSampleResult.TEXT);
        ByteArrayOutputStream text= new ByteArrayOutputStream(200);
        e.printStackTrace(new PrintStream(text));
        res.setResponseData(text.toByteArray());
        res.setResponseCode(NON_HTTP_RESPONSE_CODE);
        res.setResponseMessage(NON_HTTP_RESPONSE_MESSAGE);
        res.setSuccessful(false);
        return res;
    }

    /**
     * Get the URL, built from its component parts.
     * 
     * @return The URL to be requested by this sampler.
     * @throws MalformedURLException
     */
    private URL getUrl() throws MalformedURLException
    {
        String pathAndQuery= "";
        if (this.getMethod().equals(HTTPSamplerBean.GET)
            && getQueryString().length() > 0)
        {
            if (this.getEncodedPath().indexOf("?") > -1)
            {
                pathAndQuery= this.getEncodedPath() + "&" + getQueryString();
            }
            else
            {
                pathAndQuery= this.getEncodedPath() + "?" + getQueryString();
            }
        }
        else
        {
            pathAndQuery= this.getEncodedPath();
        }
        if (!pathAndQuery.startsWith("/"))
        {
            pathAndQuery= "/" + pathAndQuery;
        }
        if (getPort() == UNSPECIFIED_PORT || getPort() == DEFAULT_HTTP_PORT)
        {
            return new URL(getProtocol(), getDomain(), pathAndQuery);
        }
        else
        {
            return new URL(
                getProtocol(),
                getDomain(),
                getPort(),
                pathAndQuery);
        }
    }

    /**
     * Gets the QueryString attribute of the UrlConfig object.
     *
     * @return    the QueryString value
     */
    private String getQueryString()
    {
        StringBuffer buf= new StringBuffer();
        PropertyIterator iter= getArguments().iterator();
        boolean first= true;
        while (iter.hasNext())
        {
            HTTPArgument item= null;
            try
            {
                item= (HTTPArgument)iter.next().getObjectValue();
            }
            catch (ClassCastException e)
            {
                item= new HTTPArgument((Argument)iter.next().getObjectValue());
            }
            if (!first)
            {
                buf.append("&");
            }
            else
            {
                first= false;
            }
            buf.append(item.getEncodedName());
            if (item.getMetaData() == null)
            {
                buf.append("=");
            }
            else
            {
                buf.append(item.getMetaData());
            }
            buf.append(item.getEncodedValue());
        }
        return buf.toString();
    }

    /**
     * Set request headers in preparation to opening a connection.
     *
     * @param conn       <code>URLConnection</code> to set headers on
     * @exception IOException  if an I/O exception occurs
     */
    private void setPostHeaders(URLConnection conn) throws IOException
    {
        throw new Error("Can't do POST yet.");
        //postWriter.setHeaders(conn, this);
    }

    /**
     * Send POST data from <code>Entry</code> to the open connection.
     *
     * @param connection <code>URLConnection</code> where POST data should
     *                   be sent
     * @exception IOException  if an I/O exception occurs
     */
    private void sendPostData(URLConnection connection) throws IOException
    {
        throw new Error("Can't do POST yet.");
        //postWriter.sendPostData(connection, this);
    }

    /**
     * Returns an <code>HttpURLConnection</code> fully ready to attempt 
     * connection. This means it sets the request method (GET or
     * POST), headers, cookies, and authorization for the URL request.
     * <p>
     * The request infos are saved into the sample result if one is provided.
     *
     * @param u                <code>URL</code> of the URL request
     * @param method            http/https
     * @param res               sample result to save request infos to 
     * @return                 <code>HttpURLConnection</code> ready for .connect
     * @exception IOException  if an I/O Exception occurs
     */
    private HttpURLConnection setupConnection(
        URL u,
        String method,
        HTTPSampleResult res)
        throws IOException
    {
        HttpURLConnection conn;
        // [Jordi <jsalvata@atg.com>]
        // I've not been able to find out why we're not using this
        // feature of HttpURLConnections and we're doing redirection
        // by hand instead. Everything would be so much simpler...
        // [/Jordi]
        // Mike: answer - it didn't work.  Maybe in JDK1.4 it works, but
        // honestly, it doesn't seem like they're working on this.
        // My longer term plan is to use Apache's home grown HTTP Client, or
        // maybe even HTTPUnit's classes.  I'm sure both would be better than
        // Sun's.

        // [sebb] Make redirect following configurable (see bug 19004)
        // They do seem to work on JVM 1.4.1_03 (Sun/WinXP)
        HttpURLConnection.setFollowRedirects(delegateRedirects);

        conn= (HttpURLConnection)u.openConnection();
        // Delegate SSL specific stuff to SSLManager so that compilation still
        // works otherwise.
        if ("https".equalsIgnoreCase(u.getProtocol()))
        {
            try
            {
                SSLManager.getInstance().setContext(conn);
            }
            catch (Exception e)
            {
                log.warn(
                    "You may have forgotten to set the ssl.provider property "
                        + "in jmeter.properties",
                    e);
            }
        }

        // a well-bahaved browser is supposed to send 'Connection: close'
        // with the last request to an HTTP server. Instead, most browsers
        // leave it to the server to close the connection after their
        // timeout period. Leave it to the JMeter user to decide.
        if (isUseKeepAlive())
        {
            conn.setRequestProperty("Connection", "keep-alive");
        }
        else
        {
            conn.setRequestProperty("Connection", "close");
        }

        conn.setRequestMethod(method);
        String hdrs=setConnectionHeaders(conn, u, getHeaderManager());
        String cookies= setConnectionCookie(conn, u, getCookieManager());
        if (res != null)
        {
            StringBuffer sb= new StringBuffer();
            if (method.equals(HTTPSamplerBean.POST))
            {
            	String q = this.getQueryString();
				res.setQueryString(q);
                sb.append("Query data:\n");
                sb.append(q);
                sb.append('\n');
            }
            if (cookies != null)
            { 
            	res.setCookies(cookies);
                sb.append("\nCookie Data:\n");
                sb.append(cookies);
                sb.append('\n');
            }
            res.setSamplerData(sb.toString());
            //TODO rather than stuff all the information in here,
            //pick it up from the individual fields later 
            
            res.setURL(u);
            res.setHTTPMethod(method);
            res.setRequestHeaders(hdrs);
        }
        setConnectionAuthorization(conn, u, getAuthManager());
        if (method.equals(HTTPSamplerBean.POST))
        {
            setPostHeaders(conn);
        }
        return conn;
    }

    //Mark Walsh 2002-08-03, modified to also parse a parameter name value
    //string, where string contains only the parameter name and no equal sign.
    /**
     * This method allows a proxy server to send over the raw text from a
     * browser's output stream to be parsed and stored correctly into the
     * UrlConfig object.
     *
     * For each name found, addEncodedArgument() is called 
     *
     * @param queryString - the query string
     * 
     */
    private void parseArguments(String queryString)
    {
        String[] args= JOrphanUtils.split(queryString, "&");
        for (int i= 0; i < args.length; i++)
        {
            // need to handle four cases:   string contains name=value
            //                              string contains name=
            //                              string contains name
            //                              empty string
            // find end of parameter name
            int endOfNameIndex= 0;
            String metaData= ""; // records the existance of an equal sign
            if (args[i].indexOf("=") != -1)
            {
                // case of name=value, name=
                endOfNameIndex= args[i].indexOf("=");
                metaData= "=";
            }
            else
            {
                metaData= "";
                if (args[i].length() > 0)
                {
                    endOfNameIndex= args[i].length(); // case name
                }
                else
                {
                    endOfNameIndex= 0; //case where name value string is empty
                }
            }
            // parse name
            String name= ""; // for empty string
            if (args[i].length() > 0)
            {
                //for non empty string
                name= args[i].substring(0, endOfNameIndex);
            }
            // parse value
            String value= "";
            if ((endOfNameIndex + 1) < args[i].length())
            {
                value= args[i].substring(endOfNameIndex + 1, args[i].length());
            }
            if (name.length() > 0)
            {
                    addEncodedArgument(name, value, metaData);
            }
        }
    }

    /**
     * Reads the response from the URL connection.
     *
     * @param conn             URL from which to read response
     * @return                 response content
     * @exception IOException  if an I/O exception occurs
     */
    private byte[] readResponse(HttpURLConnection conn) throws IOException
    {
        byte[] readBuffer= JMeterContextService.getContext().getReadBuffer();
        BufferedInputStream in;
        boolean logError=false; // Should we log the error?
        try
        {
            if (conn.getContentEncoding() != null
                && conn.getContentEncoding().equals("gzip"))
            {
                in=
                    new BufferedInputStream(
                        new GZIPInputStream(conn.getInputStream()));
            }
            else
            {
                in= new BufferedInputStream(conn.getInputStream());
            }
        }
        catch (IOException e)
        {
            if (e.getCause() instanceof FileNotFoundException)
            {
                log.warn(e.getCause().toString());
            }
            else
            {
                log.error(e.toString());
                Throwable cause = e.getCause();
                if (cause != null){
                	log.error("Cause: "+cause);
                }
                logError=true;
            }
            in= new BufferedInputStream(conn.getErrorStream());
        }
        catch (Exception e)
        {
            log.error(e.toString());
			Throwable cause = e.getCause();
			if (cause != null){
				log.error("Cause: "+cause);
			}
            in= new BufferedInputStream(conn.getErrorStream());
			logError=true;
        }
        java.io.ByteArrayOutputStream w= new ByteArrayOutputStream();
        int x= 0;
        while ((x= in.read(readBuffer)) > -1)
        {
            w.write(readBuffer, 0, x);
        }
        in.close();
        w.flush();
        w.close();
        if (logError)
        {
        	String s;
        	if (w.size() > 1000){
				s="\n"+w.toString().substring(0,1000)+"\n\t...";
        	} else {
				s="\n"+w.toString();
        	}
        	log.error(s);
        }
        return w.toByteArray();
    }

    /**
     * Gets the ResponseHeaders from the URLConnection
     *
     * @param conn  connection from which the headers are read
     * @return string containing the headers, one per line
     */
    private String getResponseHeaders(HttpURLConnection conn)
        throws IOException
    {
        StringBuffer headerBuf= new StringBuffer();
		headerBuf.append(conn.getHeaderField(0));//Leave header as is 
//        headerBuf.append(conn.getHeaderField(0).substring(0, 8));
//        headerBuf.append(" ");
//        headerBuf.append(conn.getResponseCode());
//        headerBuf.append(" ");
//        headerBuf.append(conn.getResponseMessage());
        headerBuf.append("\n");

        for (int i= 1; conn.getHeaderFieldKey(i) != null; i++)
        {
            if (!conn //TODO - why is this not saved?
                .getHeaderFieldKey(i)
                .equalsIgnoreCase("transfer-encoding"))
            {
                headerBuf.append(conn.getHeaderFieldKey(i));
                headerBuf.append(": ");
                headerBuf.append(conn.getHeaderField(i));
                headerBuf.append("\n");
            }
        }
        return headerBuf.toString();
    }

    /**
     * Extracts all the required cookies for that particular URL request and
     * sets them in the <code>HttpURLConnection</code> passed in.
     *
     * @param conn          <code>HttpUrlConnection</code> which represents the
     *                      URL request
     * @param u             <code>URL</code> of the URL request
     * @param cookieManager the <code>CookieManager</code> containing all the
     *                      cookies for this <code>UrlConfig</code>
     */
    private String setConnectionCookie(
        HttpURLConnection conn,
        URL u,
        CookieManager cookieManager)
    {
        String cookieHeader= null;
        if (cookieManager != null)
        {
            cookieHeader= cookieManager.getCookieHeaderForURL(u);
            if (cookieHeader != null)
            {
                conn.setRequestProperty("Cookie", cookieHeader);
            }
        }
        return cookieHeader;
    }

    /**
     * Extracts all the required headers for that particular URL request and
     * sets them in the <code>HttpURLConnection</code> passed in
     *
     *@param conn           <code>HttpUrlConnection</code> which represents the
     *                      URL request
     *@param u              <code>URL</code> of the URL request
     *@param headerManager  the <code>HeaderManager</code> containing all the
     *                      cookies for this <code>UrlConfig</code>
     * @return the headers as a string
     */
    private String setConnectionHeaders(
        HttpURLConnection conn,
        URL u,
        HeaderManager headerManager)
    {
    	StringBuffer hdrs = new StringBuffer(100);
        if (headerManager != null)
        {
            CollectionProperty headers= headerManager.getHeaders();
            if (headers != null)
            {
                PropertyIterator i= headers.iterator();
                while (i.hasNext())
                {
                    Header header= (Header)i.next().getObjectValue();
                    String n=header.getName();
                    String v=header.getValue();
                    conn.setRequestProperty(n,v);
                    hdrs.append(n);  
					hdrs.append(": ");  
					hdrs.append(v);  
					hdrs.append("\n");  
                }
            }
        }
        return hdrs.toString();
    }

    /**
     * Extracts all the required authorization for that particular URL request
     * and sets it in the <code>HttpURLConnection</code> passed in.
     *
     * @param conn        <code>HttpUrlConnection</code> which represents the
     *                    URL request
     * @param u           <code>URL</code> of the URL request
     * @param authManager the <code>AuthManager</code> containing all the
     *                    cookies for this <code>UrlConfig</code>
     */
    private void setConnectionAuthorization(
        HttpURLConnection conn,
        URL u,
        AuthManager authManager)
    {
        if (authManager != null)
        {
            String authHeader= authManager.getAuthHeaderForURL(u);
            if (authHeader != null)
            {
                conn.setRequestProperty("Authorization", authHeader);
            }
        }
    }

    /**
     * Samples the URL passed in and stores the result in
     * <code>HTTPSampleResult</code>, following redirects and downloading
     * page resources as appropriate.
     * <p>
     * When getting a redirect target, redirects are not followed and 
     * resources are not downloaded. The caller will take care of this.
     *
     * @param url           URL to sample
     * @param method        HTTP method: GET, POST,...
     * @param areFollowingRedirect whether we're getting a redirect target
     * @param frameDepth    Depth of this target in the frame structure.
     *                      Used only to prevent infinite recursion.
     * @return              results of the sampling
     */
    private HTTPSampleResult sample(
        URL url,
        String method,
        boolean areFollowingRedirect,
        int frameDepth)
    {
        HttpURLConnection conn= null;

		String urlStr = url.toString();
		log.debug("Start : sample" + urlStr);

        HTTPSampleResult res= new HTTPSampleResult();

		res.setSampleLabel(urlStr);
		res.sampleStart(); // Count the retries as well in the time

        try
        {
            // Sampling proper - establish the connection and read the response:
            // Repeatedly try to connect:
            int retry;
            for (retry= 1; retry <= MAX_CONN_RETRIES; retry++)
            {
                try
                {
                    conn= setupConnection(url, method, res);
                    // Attempt the connection:
                    conn.connect();
                    break;
                }
                catch (BindException e)
                {
                    if (retry >= MAX_CONN_RETRIES)
                    {
                        log.error("Can't connect", e);
                        throw e;
                    }
                    log.debug("Bind exception, try again");
                    conn.disconnect();
                    this.setUseKeepAlive(false);
                    continue; // try again
                }
                catch (IOException e)
                {
                    log.debug("Connection failed, giving up");
                    throw e;
                }
            }
            if (retry > MAX_CONN_RETRIES)
            {
                // This should never happen, but...
                throw new BindException();
            }
            // Nice, we've got a connection. Finish sending the request:
            if (method.equals(HTTPSamplerBean.POST))
            {
                sendPostData(conn);
            }
            // Request sent. Now get the response:
            byte[] responseData= readResponse(conn);

             res.sampleEnd();
            // Done with the sampling proper.

            // Now collect the results into the HTTPSampleResult:

            res.setResponseData(responseData);

            int errorLevel= conn.getResponseCode();
            res.setResponseCode(Integer.toString(errorLevel));
            res.setSuccessful(200 <= errorLevel && errorLevel <= 399);

            res.setResponseMessage(conn.getResponseMessage());

            String ct= conn.getHeaderField("Content-type");
            res.setContentType(ct);
            if (ct != null && ct.startsWith("image/"))
            {
                res.setDataType(HTTPSampleResult.BINARY);
            }
            else
            {
                res.setDataType(HTTPSampleResult.TEXT);
            }

            res.setResponseHeaders(getResponseHeaders(conn));
            if (res.isRedirect())
            {
                res.setRedirectLocation(conn.getHeaderField("Location"));
            }

            // Store any cookies received in the cookie manager:
            saveConnectionCookies(conn, url, getCookieManager());

            // Follow redirects and download page resources if appropriate:
            if (!areFollowingRedirect)
            {
                boolean didFollowRedirects= false;
                if (res.isRedirect())
                {
                    log.debug("Location set to - " + res.getRedirectLocation());
                    
                    if (isFollowRedirects())
                    {
                        res= followRedirects(res, frameDepth);
                        didFollowRedirects= true;
                    }
                }

                if (isImageParser()
                    && res.getDataType().equals(HTTPSampleResult.TEXT)
                    && res.isSuccessful())
                {
                    if (frameDepth > MAX_FRAME_DEPTH)
                    {
                        res.addSubResult(
                            errorResult(
                                new Exception("Maximum frame/iframe nesting depth exceeded."),
                                null,
                                0));
                    }
                    else
                    {
                        // If we followed redirects, we already have a container:
                        boolean createContainerResults= !didFollowRedirects;

                        res=
                            downloadPageResources(
                                res,
                                createContainerResults,
                                frameDepth);
                    }
                }
            }

            log.debug("End : sample");
            return res;
        }
        catch (IOException e)
        {
        	res.sampleEnd();
            return errorResult(e, url.toString(), res.getTime());
        }
        finally
        {
            // calling disconnect doesn't close the connection immediately,
            // but indicates we're through with it.  The JVM should close
            // it when necessary.
            disconnect(conn);
        }
    }

    /**
     * Iteratively download the redirect targets of a redirect response.
     * <p>
     * The returned result will contain one subsample for each request issued,
     * including the original one that was passed in. It will be an
     * HTTPSampleResult that should mostly look as if the final destination
     * of the redirect chain had been obtained in a single shot.
     * 
     * @param res result of the initial request - must be a redirect response
     * @param frameDepth    Depth of this target in the frame structure.
     *                      Used only to prevent infinite recursion.
     * @return "Container" result with one subsample per request issued
     */
    private HTTPSampleResult followRedirects(
        HTTPSampleResult res,
        int frameDepth)
    {
        HTTPSampleResult totalRes= new HTTPSampleResult(res);
        HTTPSampleResult lastRes= res;

        int redirect;
        for (redirect= 0; redirect < MAX_REDIRECTS; redirect++)
        {
            String location= lastRes.getRedirectLocation();
            try
            {
                lastRes=
                    sample(
                        new URL(lastRes.getURL(), location),
                        HTTPSamplerBean.GET,
                        true,
                        frameDepth);
            }
            catch (MalformedURLException e)
            {
                lastRes= errorResult(e, location, 0);
            }
            totalRes.addSubResult(lastRes);

            if (!lastRes.isRedirect())
            {
                break;
            }
        }
        if (redirect >= MAX_REDIRECTS)
        {
            lastRes=
                errorResult(
                    new IOException("Exceeeded maximum number of redirects: "+MAX_REDIRECTS),
                    null,
                    0);
            totalRes.addSubResult(lastRes);
        }

        // Now populate the any totalRes fields that need to
        // come from lastRes:
		totalRes.sampleEnd();

        totalRes.setSampleLabel(
            totalRes.getSampleLabel() + "->" + lastRes.getSampleLabel());
        // The following three can be discussed: should they be from the
        // first request or from the final one? I chose to do it this way
        // because that's what browsers do: they show the final URL of the
        // redirect chain in the location field. 
        totalRes.setURL(lastRes.getURL());
        totalRes.setHTTPMethod(lastRes.getHTTPMethod());
        totalRes.setRequestHeaders(lastRes.getRequestHeaders());

        totalRes.setResponseData(lastRes.getResponseData());
        totalRes.setResponseCode(lastRes.getResponseCode());
        totalRes.setSuccessful(lastRes.isSuccessful());
        totalRes.setResponseMessage(lastRes.getResponseMessage());
        totalRes.setDataType(lastRes.getDataType());
        totalRes.setResponseHeaders(lastRes.getResponseHeaders());
        return totalRes;
    }

    /**
     * Download the resources of an HTML page.
     * <p>
     * If createContainerResult is true, the returned result will contain one 
     * subsample for each request issued, including the original one that was 
     * passed in. It will otherwise look exactly like that original one.
     * <p>
     * If createContainerResult is false, one subsample will be added to the
     * provided result for each requests issued.
     * 
     * @param res           result of the initial request - must contain an HTML
     *                      response
     * @param createContainerResult whether to create a "container" or just
     *                      use the provided <code>res</code> for that purpose
     * @param frameDepth    Depth of this target in the frame structure.
     *                      Used only to prevent infinite recursion.
     * @return              "Container" result with one subsample per request
     *                      issued
     */
    private HTTPSampleResult downloadPageResources(
        HTTPSampleResult res,
        boolean createContainerResult,
        int frameDepth)
    {
        Iterator urls= null;
        try
        {
            urls=
                HTMLParser.getParser().getEmbeddedResourceURLs(
                    res.getResponseData(),
                    res.getURL());
        }
        catch (HTMLParseException e)
        {
            // Don't break the world just because this failed:
            res.addSubResult(errorResult(e, null, 0));
            res.setSuccessful(false);
        }

        // Iterate through the URLs and download each image:
        if (urls != null && urls.hasNext())
        {
            if (createContainerResult)
            {
                res= new HTTPSampleResult(res);
            }

            while (urls.hasNext())
            {
                Object binURL= urls.next();
                try
                {
                    HTTPSampleResult binRes=
                        sample(
                            (URL)binURL,
                            HTTPSamplerBean.GET,
                            false,
                            frameDepth + 1);
                    res.addSubResult(binRes);
                    res.sampleEnd();
                    res.setSuccessful(
                        res.isSuccessful() && binRes.isSuccessful());
                }
                catch (ClassCastException e)
                {
                    res.addSubResult(
                        errorResult(
                            new Exception(binURL + " is not a correct URI"),
                            null,
                            0));
                    res.setSuccessful(false);
                    continue;
                }
            }
        }
        return res;
    }

    private void disconnect(HttpURLConnection conn)
    {
    	if (conn == null) return;
        String connection= conn.getHeaderField("Connection");
        String protocol= conn.getHeaderField(0);
        if ((connection == null && (protocol == null || !protocol.startsWith("HTTP/1.1")))
            || (connection != null && connection.equalsIgnoreCase("close")))
        {
            conn.disconnect();
        }
    }

    /**
     * From the <code>HttpURLConnection</code>, store all the "set-cookie"
     * key-pair values in the cookieManager of the <code>UrlConfig</code>.
     *
     * @param conn          <code>HttpUrlConnection</code> which represents the
     *                      URL request
     * @param u             <code>URL</code> of the URL request
     * @param cookieManager the <code>CookieManager</code> containing all the
     *                      cookies for this <code>UrlConfig</code>
     */
    private void saveConnectionCookies(
        HttpURLConnection conn,
        URL u,
        CookieManager cookieManager)
    {
        if (cookieManager != null)
        {
            for (int i= 1; conn.getHeaderFieldKey(i) != null; i++)
            {
                if (conn.getHeaderFieldKey(i).equalsIgnoreCase("set-cookie"))
                {
                    cookieManager.addCookieFromHeader(
                        conn.getHeaderField(i),
                        u);
                }
            }
        }
    }

    private String encodePath(String path)
    {
        // TODO JDK1.4 
        // this seems to be equivalent to path.replaceAll(" ","%20");
        path=
            Util.substitute(
                (Perl5Matcher)localMatcher.get(),
                patternCache.getPattern(
                    " ",
                    Perl5Compiler.READ_ONLY_MASK
                        & Perl5Compiler.SINGLELINE_MASK),
                spaceSub,
                path,
                Util.SUBSTITUTE_ALL);
        return path;
    }

    /**
     * @return
     */
    public Arguments getArguments()
    {
        return arguments;
    }

    /**
     * @return
     */
    private AuthManager getAuthManager()
    {
        return authManager;
    }

    /**
     * @return
     */
    private CookieManager getCookieManager()
    {
        return cookieManager;
    }

    /**
     * @return
     */
    public String getDomain()
    {
        return domain;
    }

    /**
     * @return
     */
    public String getEncodedPath()
    {
        return encodedPath;
    }

    /**
     * @return
     */
    public String getFileField()
    {
        return fileField;
    }

    /**
     * @return
     */
    public File getFilename()
    {
        return filename;
    }

    /**
     * @return
     */
    public boolean isFollowRedirects()
    {
        return followRedirects;
    }

    /**
     * @return
     */
    private HeaderManager getHeaderManager()
    {
        return headerManager;
    }

    /**
     * @return
     */
    public boolean isImageParser()
    {
        return imageParser;
    }

    /**
     * @return
     */
    public String getMethod()
    {
        return Method;
    }

    /**
     * @return
     */
    public String getMimeType()
    {
        return mimeType;
    }

    /**
     * @return
     */
    public String getPath()
    {
        return path;
    }

    public int getPort()
    {
        if (port == UNSPECIFIED_PORT)
        {
            if ("https".equalsIgnoreCase(getProtocol()))
            {
                return DEFAULT_HTTPS_PORT;
            }
            return DEFAULT_HTTP_PORT;
        }
        else
        {
            return port;
        }
    }

    public String getProtocol()
    {
        if (protocol == null || protocol.equals(""))
        {
            return DEFAULT_PROTOCOL;
        }
        else
        {
            return protocol;
        }
    }

    /**
     * @return
     */
    public boolean isUseKeepAlive()
    {
        return useKeepAlive;
    }

    /**
     * @param arguments
     */
    public void setArguments(Arguments arguments)
    {
        this.arguments= arguments;
    }

    /**
     * @param manager
     */
    public void setAuthManager(AuthManager manager)
    {
        authManager= manager;
    }

    /**
     * @param manager
     */
    public void setCookieManager(CookieManager manager)
    {
        cookieManager= manager;
    }

    /**
     * @param string
     */
    public void setDomain(String string)
    {
        domain= string;
    }

    /**
     * This property is not writable from the outside, but set automatically
     * by setPath(String).
     * 
     * @param string
     */
    private void setEncodedPath(String string)
    {
        encodedPath= string;
    }

    /**
     * @param string
     */
    public void setFileField(String string)
    {
        fileField= string;
    }

    /**
     * @param string
     */
    public void setFilename(File file)
    {
        filename= file;
    }

    /**
     * @param b
     */
    public void setFollowRedirects(boolean b)
    {
        followRedirects= b;
    }

    /**
     * @param manager
     */
    public void setHeaderManager(HeaderManager manager)
    {
        headerManager= manager;
    }

    /**
     * @param b
     */
    public void setImageParser(boolean b)
    {
        imageParser= b;
    }

    /**
     * @param string
     */
    public void setMethod(String string)
    {
        Method= string;
    }

    /**
     * @param string
     */
    public void setMimeType(String string)
    {
        mimeType= string;
    }

    /**
     * Sets the Path attribute of the UrlConfig object.
     * Also calls parseArguments to extract and store any
     * query arguments and sets the encodedPath property.
     *  
     *@param  string  The new Path value
     */
    public void setPath(String string)
    {
        if (GET.equals(getMethod()))
        {
            int index= string.indexOf("?");
            if (index > -1)
            {
                path= string.substring(0, index);
                parseArguments(string.substring(index + 1));
            }
            else
            {
                path= string;
            }
        }
        else
        {
            path= string;
        }
        setEncodedPath(encodePath(path));
    }

    /**
     * @param i
     */
    public void setPort(int i)
    {
        port= i;
    }

    /**
     * @param string
     */
    public void setProtocol(String string)
    {
        protocol= string;
    }

    /**
     * @param b
     */
    public void setUseKeepAlive(boolean b)
    {
        useKeepAlive= b;
    }

    public static class Test extends junit.framework.TestCase
    {
        public Test(String name)
        {
            super(name);
        }

        public void testArgumentWithoutEquals() throws Exception
        {
            HTTPSamplerBean sampler= new HTTPSamplerBean();
            sampler.setProtocol("http");
            sampler.setMethod(HTTPSamplerBean.GET);
            sampler.setPath("/index.html?pear");
            sampler.setDomain("www.apache.org");
            assertEquals(
                "http://www.apache.org/index.html?pear",
                sampler.getUrl().toString());
        }

        public void testMakingUrl() throws Exception
        {
            HTTPSamplerBean config= new HTTPSamplerBean();
            config.setProtocol("http");
            config.setMethod(HTTPSamplerBean.GET);
            config.addArgument("param1", "value1");
            config.setPath("/index.html");
            config.setDomain("www.apache.org");
            assertEquals(
                "http://www.apache.org/index.html?param1=value1",
                config.getUrl().toString());
        }
        public void testMakingUrl2() throws Exception
        {
            HTTPSamplerBean config= new HTTPSamplerBean();
            config.setProtocol("http");
            config.setMethod(HTTPSamplerBean.GET);
            config.addArgument("param1", "value1");
            config.setPath("/index.html?p1=p2");
            config.setDomain("www.apache.org");
            assertEquals(
                "http://www.apache.org/index.html?param1=value1&p1=p2",
                config.getUrl().toString());
        }
        public void testMakingUrl3() throws Exception
        {
            HTTPSamplerBean config= new HTTPSamplerBean();
            config.setProtocol("http");
            config.setMethod(HTTPSamplerBean.POST);
            config.addArgument("param1", "value1");
            config.setPath("/index.html?p1=p2");
            config.setDomain("www.apache.org");
            assertEquals(
                "http://www.apache.org/index.html?p1=p2",
                config.getUrl().toString());
        }

        // test cases for making Url, and exercise method
        // addArgument(String name,String value,String metadata)

        public void testMakingUrl4() throws Exception
        {
            HTTPSamplerBean config= new HTTPSamplerBean();
            config.setProtocol("http");
            config.setMethod(HTTPSamplerBean.GET);
            config.addArgument("param1", "value1", "=");
            config.setPath("/index.html");
            config.setDomain("www.apache.org");
            assertEquals(
                "http://www.apache.org/index.html?param1=value1",
                config.getUrl().toString());
        }
        public void testMakingUrl5() throws Exception
        {
            HTTPSamplerBean config= new HTTPSamplerBean();
            config.setProtocol("http");
            config.setMethod(HTTPSamplerBean.GET);
            config.addArgument("param1", "", "=");
            config.setPath("/index.html");
            config.setDomain("www.apache.org");
            assertEquals(
                "http://www.apache.org/index.html?param1=",
                config.getUrl().toString());
        }
        public void testMakingUrl6() throws Exception
        {
            HTTPSamplerBean config= new HTTPSamplerBean();
            config.setProtocol("http");
            config.setMethod(HTTPSamplerBean.GET);
            config.addArgument("param1", "", "");
            config.setPath("/index.html");
            config.setDomain("www.apache.org");
            assertEquals(
                "http://www.apache.org/index.html?param1",
                config.getUrl().toString());
        }

        // test cases for making Url, and exercise method
        // parseArguments(String queryString)

        public void testMakingUrl7() throws Exception
        {
            HTTPSamplerBean config= new HTTPSamplerBean();
            config.setProtocol("http");
            config.setMethod(HTTPSamplerBean.GET);
            config.parseArguments("param1=value1");
            config.setPath("/index.html");
            config.setDomain("www.apache.org");
            assertEquals(
                "http://www.apache.org/index.html?param1=value1",
                config.getUrl().toString());
        }

        public void testMakingUrl8() throws Exception
        {
            HTTPSamplerBean config= new HTTPSamplerBean();
            config.setProtocol("http");
            config.setMethod(HTTPSamplerBean.GET);
            config.parseArguments("param1=");
            config.setPath("/index.html");
            config.setDomain("www.apache.org");
            assertEquals(
                "http://www.apache.org/index.html?param1=",
                config.getUrl().toString());
        }

        public void testMakingUrl9() throws Exception
        {
            HTTPSamplerBean config= new HTTPSamplerBean();
            config.setProtocol("http");
            config.setMethod(HTTPSamplerBean.GET);
            config.parseArguments("param1");
            config.setPath("/index.html");
            config.setDomain("www.apache.org");
            assertEquals(
                "http://www.apache.org/index.html?param1",
                config.getUrl().toString());
        }

        public void testMakingUrl10() throws Exception
        {
            HTTPSamplerBean config= new HTTPSamplerBean();
            config.setProtocol("http");
            config.setMethod(HTTPSamplerBean.GET);
            config.parseArguments("");
            config.setPath("/index.html");
            config.setDomain("www.apache.org");
            assertEquals(
                "http://www.apache.org/index.html",
                config.getUrl().toString());
        }
    }
}