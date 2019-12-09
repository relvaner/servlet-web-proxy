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
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A proxy servlet in which the target URI is templated from incoming request
 * parameters. The format adheres to the
 * <a href="http://tools.ietf.org/html/rfc6570">URI Template RFC</a>, "Level 1".
 * Example:
 * 
 * <pre>
 *   targetUri = http://{host}:{port}/{path}
 * </pre>
 * 
 * --which has the template variables. The incoming request must contain query
 * args of these names. They are removed when the request is sent to the target.
 */
public class URITemplateProxyServlet extends AbstractProxyServlet {
	protected static final long serialVersionUID = -1765247685360288946L;
	
	/*
	 * Rich: It might be a nice addition to have some syntax that allowed a
	 * proxy arg to be "optional", that is, don't fail if not present, just
	 * return the empty string or a given default. But I don't see anything in
	 * the spec that supports this kind of construct. Notionally, it might look
	 * like {?host:google.com} would return the value of the URL parameter
	 * "?hostProxyArg=somehost.com" if defined, but if not defined, return
	 * "google.com". Similarly, {?host} could return the value of hostProxyArg
	 * or empty string if not present. But that's not how the spec works. So for
	 * now we will require a proxy arg to be present if defined for this proxy
	 * URL.
	 */
	protected static final String P_TARGET_URI = "targetUri";
	
	public static final String P_DOMAIN = "domain";
	protected String doDomain = "localhost";
	
	protected static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{(.+?)\\}");
	protected static final String ATTR_QUERY_STRING = URITemplateProxyServlet.class.getSimpleName() + ".queryString";

	protected String templateUri;// has {name} parts

	@Override
	protected void createProxyClient() {
		proxyClient = new URITemplateHTTPProxyClient(getServletName(), ATTR_QUERY_STRING);
	}
	
	@Override
	public void init() throws ServletException {
		templateUri = getConfigParam(P_TARGET_URI);
		if (templateUri == null)
			throw new ServletException(P_TARGET_URI + " is required.");
		
		String doDomainStr = getConfigParam(P_DOMAIN);
		if (doDomainStr != null)
			doDomain = doDomainStr;
		
		super.init();
	}
	
	protected boolean containsResource(String uri) {
		int index = uri.lastIndexOf(".");
		return index != -1 && uri.substring(index).length() > 1;
	}
	
	public boolean execute(HttpServletRequest servletRequest, HttpServletResponse servletResponse, String oldTargetUri) throws ServletException, IOException  {
		// First collect params
		/*
		 * Do not use servletRequest.getParameter(arg) because that will
		 * typically read and consume the servlet InputStream (where our form
		 * data is stored for POST). We need the InputStream later on. So we'll
		 * parse the query string ourselves. A side benefit is we can keep the
		 * proxy parameters in the query string and not have to add them to a
		 * URL encoded form attachment.
		 */
		String requestQueryString = servletRequest.getQueryString();
		String queryString = "";
		if (requestQueryString != null)
			queryString = "?" + requestQueryString;//no "?" but might have "#"

		int hash = queryString.indexOf('#');
		if (hash >= 0) {
			queryString = queryString.substring(0, hash);
		}
		List<NameValuePair> pairs;
		try {
			// note: HttpClient 4.2 lets you parse the string without building
			// the URI
			pairs = URLEncodedUtils.parse(new URI(queryString), "UTF-8");
		} catch (URISyntaxException e) {
			throw new ServletException("Unexpected URI parsing error on " + queryString, e);
		}
		LinkedHashMap<String, String> params = new LinkedHashMap<String, String>();
		for (NameValuePair pair : pairs) {
			params.put(pair.getName(), pair.getValue());
		}

		// Now rewrite the URL
		StringBuffer urlBuf = new StringBuffer();// note: StringBuilder isn't
													// supported by Matcher
		Matcher matcher = TEMPLATE_PATTERN.matcher(templateUri);
		while (matcher.find()) {
			String arg = matcher.group(1);
			String replacement = params.remove(arg);// note we remove
			if (replacement == null) {
				if (oldTargetUri==null)
					throw new ServletException("Missing HTTP parameter " + arg + " to fill the template");
				else
					return false;
			}
			matcher.appendReplacement(urlBuf, replacement);
		}
		matcher.appendTail(urlBuf);
		
		String temporaryTargetUri = urlBuf.toString();
		URI temporaryTargetUriObj = null;
		try {
			temporaryTargetUriObj = new URI(temporaryTargetUri);
		} catch (Exception e) {
			throw new ServletException("Rewritten targetUri is invalid: " + temporaryTargetUri, e);
		}

		// Determine the new query string based on removing the used names
		StringBuilder newQueryBuf = new StringBuilder(queryString.length());
		for (Map.Entry<String, String> nameVal : params.entrySet()) {
			if (newQueryBuf.length() > 0)
				newQueryBuf.append('&');
			newQueryBuf.append(nameVal.getKey()).append('=');
			if (nameVal.getValue() != null)
				newQueryBuf.append(nameVal.getValue());
		}
		servletRequest.setAttribute(ATTR_QUERY_STRING, newQueryBuf.toString());
		
		TargetUriCookie.set(servletRequest, servletResponse, doDomain, temporaryTargetUri);
		// TODO: replaceFirst("uri")
		String pathInfo = servletRequest.getPathInfo();
		if (pathInfo!=null)
			pathInfo = servletRequest.getPathInfo().replace("uri", "");
		doService(servletRequest, servletResponse, temporaryTargetUri, temporaryTargetUriObj, pathInfo, new MutableBoolean(true), null, false, null, null);
		
		return true;
	}

	@Override
	protected void service(HttpServletRequest servletRequest, HttpServletResponse servletResponse) throws ServletException, IOException {
		String temporaryTargetUri = TargetUriCookie.get(servletRequest);
		boolean executed = false;
		
		if (servletRequest.getQueryString()!=null) {
			executed = execute(servletRequest, servletResponse, temporaryTargetUri);
		}
		if (!executed) {
			URI temporaryTargetUriObj = null;
			try {
				temporaryTargetUriObj = new URI(temporaryTargetUri);
			} catch (Exception e) {
				throw new ServletException("Rewritten targetUri is invalid: " + temporaryTargetUri, e);
			}
			TargetUriCookie.set(servletRequest, servletResponse, doDomain, temporaryTargetUri);
			servletRequest.setAttribute(ATTR_QUERY_STRING, servletRequest.getQueryString());
			doService(servletRequest, servletResponse, temporaryTargetUri, temporaryTargetUriObj, servletRequest.getPathInfo(), new MutableBoolean(true), null, false, null, null);
		}
	}
}
