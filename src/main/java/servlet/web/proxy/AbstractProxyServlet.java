/*
 * Copyright MITRE
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package servlet.web.proxy;

import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.http.Header;
import org.apache.log4j.Level;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static servlet.web.proxy.ProxyLogger.*;

import java.io.IOException;
import java.net.URI;
import java.util.function.Function;

/**
 * An HTTP reverse proxy/gateway servlet. It is designed to be extended for
 * customization if desired. Most of the work is handled by
 * <a href="http://hc.apache.org/httpcomponents-client-ga/">Apache
 * HttpClient</a>.
 * <p>
 * There are alternatives to a servlet based proxy such as Apache mod_proxy if
 * that is available to you. However this servlet is easily customizable by
 * Java, secure-able by your web application's security (e.g. spring-security),
 * portable across servlet engines, and is embeddable into another web
 * application.
 * </p>
 * <p>
 * Inspiration: http://httpd.apache.org/docs/2.0/mod/mod_proxy.html
 * </p>
 *
 * @author David Smiley dsmiley@mitre.org
 */
public class AbstractProxyServlet extends HttpServlet {
	protected static final long serialVersionUID = 3020538430776428820L;

	/* INIT PARAMETER NAME CONSTANTS */
	/**
	 * A boolean parameter name to enable logging of input and target URLs to
	 * the servlet log.
	 */
	public static final String P_LOG = "log";

	/** A boolean parameter name to enable forwarding of the client IP */
	public static final String P_FORWARDED_FOR = "forwardip";

	/** A boolean parameter name to keep HOST parameter as-is */
	public static final String P_PRESERVE_HOST = "preserveHost";

	/** A boolean parameter name to keep COOKIES as-is */
	public static final String P_PRESERVE_COOKIES = "preserveCookies";
	public static final String P_PRESERVE_COOKIES_CONTEXT_PATH = "preserveCookiesContextPath";
	public static final String P_PRESERVE_COOKIES_SERVLET_PATH = "preserveCookiesServletPath";

	/** A boolean parameter name to have auto-handle redirects */
	public static final String P_HANDLE_REDIRECTS = "http.protocol.handle-redirects"; // ClientPNames.HANDLE_REDIRECTS

	public static final String P_PRESERVE_HOSTNAME_VERIFICATION = "preserveHostnameVerification";

	/** A integer parameter name to set the socket connection timeout (millis) */
	public static final String P_CONNECT_TIMEOUT = "http.socket.timeout"; // CoreConnectionPNames.SO_TIMEOUT
	/** A integer parameter name to set the socket read timeout (millis) */
	public static final String P_READTIMEOUT = "http.read.timeout";

	/* MISC */

	protected boolean doLog = false;
	/** User agents shouldn't send the url fragment but what if it does? */
	//protected boolean doSendUrlFragment = true;

	protected HTTPProxyClient proxyClient;

	@Override
	public String getServletInfo() {
		return "A proxy servlet by David Smiley, dsmiley@apache.org";
	}

	/**
	 * Reads a configuration parameter. By default it reads servlet init
	 * parameters but it can be overridden.
	 */
	protected String getConfigParam(String key) {
		return getServletConfig().getInitParameter(key);
	}
	
	protected void createProxyClient() {
		proxyClient = new HTTPProxyClient(getServletName());
	}

	@Override
	public void init() throws ServletException {
		createProxyClient();
		
		String doLogStr = getConfigParam(P_LOG);
		if (doLogStr != null) {
			doLog = Boolean.parseBoolean(doLogStr);
			proxyClient.doLog = doLog;

			if (!this.doLog)
				logger().setLevel(Level.INFO);
		}

		String doForwardIPString = getConfigParam(P_FORWARDED_FOR);
		if (doForwardIPString != null) {
			proxyClient.doForwardIP = Boolean.parseBoolean(doForwardIPString);
		}

		String preserveHostString = getConfigParam(P_PRESERVE_HOST);
		if (preserveHostString != null) {
			proxyClient.doPreserveHost = Boolean.parseBoolean(preserveHostString);
		}

		String preserveCookiesString = getConfigParam(P_PRESERVE_COOKIES);
		if (preserveCookiesString != null) {
			proxyClient.doPreserveCookies = Boolean.parseBoolean(preserveCookiesString);
		}

		String preserveCookiesContextPathString = getConfigParam(P_PRESERVE_COOKIES_CONTEXT_PATH);
		if (preserveCookiesContextPathString != null) {
			proxyClient.doPreserveCookiesContextPath = Boolean.parseBoolean(preserveCookiesContextPathString);
		}
		
		String preserveCookiesServletPathString = getConfigParam(P_PRESERVE_COOKIES_SERVLET_PATH);
		if (preserveCookiesServletPathString != null) {
			proxyClient.doPreserveCookiesServletPath = Boolean.parseBoolean(preserveCookiesServletPathString);
		}

		String handleRedirectsString = getConfigParam(P_HANDLE_REDIRECTS);
		if (handleRedirectsString != null) {
			proxyClient.doHandleRedirects = Boolean.parseBoolean(handleRedirectsString);
		}

		String preserveHostnameVerificationString = getConfigParam(P_PRESERVE_HOSTNAME_VERIFICATION);
		if (preserveHostnameVerificationString != null) {
			proxyClient.doPreserveHostnameVerification = Boolean.parseBoolean(preserveHostnameVerificationString);
		}

		String connectTimeoutString = getConfigParam(P_CONNECT_TIMEOUT);
		if (connectTimeoutString != null) {
			proxyClient.connectTimeout = Integer.parseInt(connectTimeoutString);
		}
		
		String readTimeoutString = getConfigParam(P_READTIMEOUT);
	    if (readTimeoutString != null) {
	    	proxyClient.readTimeout = Integer.parseInt(readTimeoutString);
	    }
	    
	    config(proxyClient);
	    
	    proxyClient.init();
	}
	
	protected void config(HTTPProxyClient proxyClient) {
		// empty
	}
	
	@Override
	public void destroy() {
		proxyClient.destroy();
		super.destroy();
	}
	
	protected URI getTargetObj(String uri) throws ServletException {
		URI result = null;

		if (uri == null)
			throw new ServletException(uri + " is required.");

		// test it's valid
		try {
			result = new URI(uri);
		} catch (Exception e) {
			e.printStackTrace();
		}

		return result;
	}

	protected byte[] doService(HttpServletRequest servletRequest, HttpServletResponse servletResponse, String targetUri,
			URI targetObj, String pathInfo, MutableBoolean resource, Function<Header, Boolean> filter,
			boolean withRequestPathInfo, String urlPattern, Function<byte[], byte[]> contentFilter) throws ServletException, IOException {
		return proxyClient.execute(servletRequest, servletResponse, targetUri, targetObj, pathInfo, resource, filter, withRequestPathInfo, urlPattern, contentFilter);
	}
}
