package servlet.web.proxy;

import static servlet.web.proxy.HTTPProxyClientUtils.*;
import static servlet.web.proxy.ProxyLogger.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpCookie;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.http.Consts;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.AbortableHttpRequest;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.message.BasicHttpEntityEnclosingRequest;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;

public class HTTPProxyClient extends AbstractHTTPProxyClient {
	protected boolean doSendUrlFragment = true;
	protected boolean doPreserveHost = false;
	protected boolean doPreserveCookies = false;
	protected boolean doForwardIP = true;
	protected boolean doPreserveCookiesContextPath = false;
	protected boolean doPreserveCookiesServletPath = false;
	
	protected Function<String, Boolean> cookieFilterRequest;
	protected Function<HttpCookie, Boolean> cookieFilterResponse;
	
	protected String servletName;
	
	public HTTPProxyClient(String servletName) {
		super();
		this.servletName = servletName;
	}
	
	public boolean isDoSendUrlFragment() {
		return doSendUrlFragment;
	}

	public void setDoSendUrlFragment(boolean doSendUrlFragment) {
		this.doSendUrlFragment = doSendUrlFragment;
	}

	public boolean isDoPreserveHost() {
		return doPreserveHost;
	}

	public void setDoPreserveHost(boolean doPreserveHost) {
		this.doPreserveHost = doPreserveHost;
	}

	public boolean isDoPreserveCookies() {
		return doPreserveCookies;
	}

	public void setDoPreserveCookies(boolean doPreserveCookies) {
		this.doPreserveCookies = doPreserveCookies;
	}

	public boolean isDoForwardIP() {
		return doForwardIP;
	}

	public void setDoForwardIP(boolean doForwardIP) {
		this.doForwardIP = doForwardIP;
	}

	public boolean isDoPreserveCookiesContextPath() {
		return doPreserveCookiesContextPath;
	}

	public void setDoPreserveCookiesContextPath(boolean doPreserveCookiesContextPath) {
		this.doPreserveCookiesContextPath = doPreserveCookiesContextPath;
	}

	public boolean isDoPreserveCookiesServletPath() {
		return doPreserveCookiesServletPath;
	}

	public void setDoPreserveCookiesServletPath(boolean doPreserveCookiesServletPath) {
		this.doPreserveCookiesServletPath = doPreserveCookiesServletPath;
	}

	public void setCookieFilterRequest(Function<String, Boolean> cookieFilterRequest) {
		this.cookieFilterRequest = cookieFilterRequest;
	}

	public void setCookieFilterResponse(Function<HttpCookie, Boolean> cookieFilterResponse) {
		this.cookieFilterResponse = cookieFilterResponse;
	}

	public byte[] execute(HttpServletRequest servletRequest, HttpServletResponse servletResponse, String targetUri,
			URI targetObj, String pathInfo, final MutableBoolean resource, final Function<Header, Boolean> filter,
			boolean withRequestPathInfo, String urlPattern, Function<byte[], byte[]> contentFilter) throws ServletException, IOException {
		byte[] result = null;

		// Make the Request
		// note: we won't transfer the protocol version because I'm not sure it
		// would truly be compatible
		String method = servletRequest.getMethod();
		String proxyRequestUri = rewriteUrlFromRequest(servletRequest, targetUri, pathInfo, withRequestPathInfo,
				urlPattern);
		logger().debug("HTTPProxyClient::Request: "+proxyRequestUri);
		HttpRequest proxyRequest;
		// spec: RFC 2616, sec 4.3: either of these two headers signal that
		// there is a message body.
		if (servletRequest.getHeader(HttpHeaders.CONTENT_LENGTH) != null
				|| servletRequest.getHeader(HttpHeaders.TRANSFER_ENCODING) != null) {
			proxyRequest = newProxyRequestWithEntity(method, proxyRequestUri, servletRequest);
		} else {
			proxyRequest = new BasicHttpRequest(method, proxyRequestUri);
		}

		copyRequestHeaders(servletRequest, proxyRequest, targetObj);

		setXForwardedForHeader(servletRequest, proxyRequest);

		HttpResponse proxyResponse = null;
		try {
			// Execute the request
			proxyResponse = doExecute(servletRequest, proxyRequest, targetObj);

			// Process the response:

			// Pass the response code. This method with the "reason phrase" is
			// deprecated but it's the
			// only way to pass the reason along too.
			int statusCode = proxyResponse.getStatusLine().getStatusCode();
			// noinspection deprecation
			servletResponse.setStatus(statusCode, proxyResponse.getStatusLine().getReasonPhrase());

			// Copying response headers to make sure SESSIONID or other Cookie
			// which comes from the remote
			// server will be saved in client when the proxied url was
			// redirected to another one.
			// See issue
			// [#51](https://github.com/mitre/HTTP-Proxy-Servlet/issues/51)
			final boolean enabled = !resource.getValue();
			final MutableBoolean contentDisposition = new MutableBoolean(false);
			final MutableBoolean contentTypeHTML = new MutableBoolean(false);
			Function<Header, Boolean> filterInternal = new Function<Header, Boolean>() {
				@Override
				public Boolean apply(Header header) {
					boolean result = false;

					if (enabled) {
						if (header.getName().equalsIgnoreCase("Content-Disposition"))
							contentDisposition.setTrue();
						else if (header.getName().equalsIgnoreCase("Content-Type")) {
							if (header.getValue().contains("text/html"))
								contentTypeHTML.setTrue();
						}
					}

					if (filter!=null)
						result = filter.apply(header);
					
					return result;
				}
			};
			copyResponseHeaders(proxyResponse, servletRequest, servletResponse, targetUri, filterInternal, withRequestPathInfo, urlPattern);
			
			if (enabled)
				resource.setValue(!(contentDisposition.isFalse() && contentTypeHTML.isTrue()));
			
			if (statusCode == HttpServletResponse.SC_NOT_MODIFIED) {
				// 304 needs special handling. See:
				// http://www.ics.uci.edu/pub/ietf/http/rfc1945.html#Code304
				// Don't send body entity/content!
				servletResponse.setIntHeader(HttpHeaders.CONTENT_LENGTH, 0);
			} else {
				// Send the content to the client
				// changed by David A. Bauer
				if (proxyResponse.getEntity()!=null) {
					ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);
					proxyResponse.getEntity().writeTo(buffer);
					buffer.flush();
					result = buffer.toByteArray();
					if (resource.getValue()) {
						if (contentFilter!=null && contentTypeHTML.isTrue()) {
							byte[] content = contentFilter.apply(result);
							servletResponse.setIntHeader(HttpHeaders.CONTENT_LENGTH, content.length);
							servletResponse.getOutputStream().write(content);
						}
						servletResponse.getOutputStream().write(result);
						servletResponse.getOutputStream().flush();
					}
					buffer.close();
				}
			}

		} catch (Exception e) {
			handleRequestException(proxyRequest, e);
		} finally {
			// make sure the entire entity was consumed, so the connection is
			// released
			if (proxyResponse != null)
				consumeQuietly(proxyResponse.getEntity());
			// Note: Don't need to close servlet outputStream:
			// http://stackoverflow.com/questions/1159168/should-one-call-close-on-httpservletresponse-getoutputstream-getwriter
		}

		return result;
	}
	
	protected void handleRequestException(HttpRequest proxyRequest, Exception e) throws ServletException, IOException {
		// abort request, according to best practice with HttpClient
		if (proxyRequest instanceof AbortableHttpRequest) {
			AbortableHttpRequest abortableHttpRequest = (AbortableHttpRequest) proxyRequest;
			abortableHttpRequest.abort();
		}
		if (e instanceof RuntimeException)
			throw (RuntimeException) e;
		if (e instanceof ServletException)
			throw (ServletException) e;
		// noinspection ConstantConditions
		if (e instanceof IOException)
			throw (IOException) e;
		throw new RuntimeException(e);
	}
	
	/**
	 * Reads the request URI from {@code servletRequest} and rewrites it,
	 * considering targetUri. It's used to make the new request.
	 */
	/**
	 * Reads the request URI from {@code servletRequest} and rewrites it,
	 * considering targetUri. It's used to make the new request.
	 */
	protected String rewriteUrlFromRequest(HttpServletRequest servletRequest, String targetUri, String pathInfo,
			boolean withRequestPathInfo, String urlPattern) {
		StringBuilder uri = new StringBuilder(500);
		uri.append(targetUri);
		// Handle the path given to the servlet
		// changed by David A. Bauer
		if (pathInfo != null)
			uri.append(encodeUriQuery(pathInfo, true));
		else if (servletRequest.getPathInfo() != null && withRequestPathInfo) {
			urlPattern = urlPattern.replace("/*", "");
			uri.append(encodeUriQuery(servletRequest.getPathInfo().substring(urlPattern.length(), servletRequest.getPathInfo().length()), true));
		}

		// Handle the query string & fragment
		String queryString = servletRequest.getQueryString();// ex:(following
																// '?'):
																// name=value&foo=bar#fragment
		String fragment = null;
		// split off fragment from queryString, updating queryString if found
		if (queryString != null) {
			int fragIdx = queryString.indexOf('#');
			if (fragIdx >= 0) {
				fragment = queryString.substring(fragIdx + 1);
				queryString = queryString.substring(0, fragIdx);
			}
		}

		queryString = rewriteQueryStringFromRequest(servletRequest, queryString);
		if (queryString != null && queryString.length() > 0) {
			uri.append('?');
			uri.append(encodeUriQuery(queryString, false));
		}

		if (doSendUrlFragment && fragment != null) {
			uri.append('#');
			uri.append(encodeUriQuery(fragment, false));
		}
		return uri.toString();
	}
	
	protected String rewriteQueryStringFromRequest(HttpServletRequest servletRequest, String queryString) {
		return queryString;
	}
	
	protected HttpRequest newProxyRequestWithEntity(String method, String proxyRequestUri,
			HttpServletRequest servletRequest) throws IOException {
		HttpEntityEnclosingRequest eProxyRequest = new BasicHttpEntityEnclosingRequest(method, proxyRequestUri);
		// Add the input entity (streamed)
		// note: we don't bother ensuring we close the servletInputStream since
		// the container handles it
		boolean isFormUrlencoded = (servletRequest.getContentType() != null
		            && servletRequest.getContentType().contains("application/x-www-form-urlencoded")
		            && "POST".equalsIgnoreCase(servletRequest.getMethod()));
		
		if (isFormUrlencoded)
			newProxyRequestWithEntityForFormUrlencoded(eProxyRequest, servletRequest);
		else	
			eProxyRequest.setEntity(new InputStreamEntity(servletRequest.getInputStream(), getContentLength(servletRequest)));
		return eProxyRequest;
	}
	
	// see: https://github.com/mitre/HTTP-Proxy-Servlet/issues/54, jackielii
	protected void newProxyRequestWithEntityForFormUrlencoded(HttpEntityEnclosingRequest eProxyRequest, HttpServletRequest servletRequest) throws IOException {
		List<NameValuePair> queryParams = Collections.emptyList();
		String queryString = servletRequest.getQueryString();
		if (queryString != null) {
			queryParams = URLEncodedUtils.parse(queryString, Consts.UTF_8);
		}
		
		Map<String, String[]> form = servletRequest.getParameterMap();
		List<NameValuePair> params = new ArrayList<NameValuePair>();

		OUTER_LOOP:
			for (Iterator<String> nameIterator = form.keySet().iterator(); nameIterator.hasNext(); ) {
				String name = nameIterator.next();
				
				// skip parameters from query string
				for (NameValuePair queryParam : queryParams) {
					if (name.equals(queryParam.getName())) {
						continue OUTER_LOOP;
					}
				}
				
				String[] value = form.get(name);
				if (value.length==1)
					params.add(new BasicNameValuePair(name, value[0]));
				else
					for (int i=0; i<value.length; i++)
						params.add(new BasicNameValuePair(name, value[i]));
			}
		
		eProxyRequest.setEntity(new UrlEncodedFormEntity(params, "UTF-8"));
	}
	
	// Get the header value as a long in order to more correctly proxy very
	// large requests
	protected long getContentLength(HttpServletRequest request) {
		String contentLengthHeader = request.getHeader("Content-Length");
		if (contentLengthHeader != null) {
			return Long.parseLong(contentLengthHeader);
		}
		return -1L;
	}
	
	/**
	 * Copy request headers from the servlet client to the proxy request. This
	 * is easily overridden to add your own.
	 */
	protected void copyRequestHeaders(HttpServletRequest servletRequest, HttpRequest proxyRequest, URI targetObj) {
		// Get an Enumeration of all of the header names sent by the client
		Enumeration<String> enumerationOfHeaderNames = servletRequest.getHeaderNames();
		while (enumerationOfHeaderNames.hasMoreElements()) {
			String headerName = enumerationOfHeaderNames.nextElement();
			copyRequestHeader(servletRequest, proxyRequest, targetObj, headerName);
		}
	}

	/**
	 * Copy a request header from the servlet client to the proxy request. This
	 * is easily overridden to filter out certain headers if desired.
	 */
	protected void copyRequestHeader(HttpServletRequest servletRequest, HttpRequest proxyRequest, URI targetObj,
			String headerName) {
		// Instead the content-length is effectively set via InputStreamEntity
		if (headerName.equalsIgnoreCase(HttpHeaders.CONTENT_LENGTH))
			return;
		if (hopByHopHeaders.containsHeader(headerName))
			return;

		Enumeration<String> headers = servletRequest.getHeaders(headerName);
		while (headers.hasMoreElements()) {// sometimes more than one value
			String headerValue = headers.nextElement();
			logger().debug("HTTPProxyClient::Request: " + headerName + ":" + headerValue); // debug
			// In case the proxy host is running multiple virtual servers,
			// rewrite the Host header to ensure that we get content from
			// the correct virtual server
			if (!doPreserveHost && headerName.equalsIgnoreCase(HttpHeaders.HOST)) {
				HttpHost host = URIUtils.extractHost(targetObj);
				headerValue = host.getHostName();
				if (host.getPort() != -1)
					headerValue += ":" + host.getPort();

			} else if (headerName.equalsIgnoreCase(org.apache.http.cookie.SM.COOKIE)) {
				headerValue = getRealCookie(servletName, headerValue, doPreserveCookies, cookieFilterRequest);
			}
			proxyRequest.addHeader(headerName, headerValue);
		}
	}
	
	protected void setXForwardedForHeader(HttpServletRequest servletRequest, HttpRequest proxyRequest) {
		if (doForwardIP) {
			String forHeaderName = "X-Forwarded-For";
			String forHeader = servletRequest.getRemoteAddr();
			String existingForHeader = servletRequest.getHeader(forHeaderName);
			if (existingForHeader != null) {
				forHeader = existingForHeader + ", " + forHeader;
			}
			proxyRequest.setHeader(forHeaderName, forHeader);

			String protoHeaderName = "X-Forwarded-Proto";
			String protoHeader = servletRequest.getScheme();
			proxyRequest.setHeader(protoHeaderName, protoHeader);
		}
	}
	
	/** Copy proxied response headers back to the servlet client. */
	protected void copyResponseHeaders(HttpResponse proxyResponse, HttpServletRequest servletRequest,
			HttpServletResponse servletResponse, String targetUri, Function<Header, Boolean> filter, boolean withRequestPathInfo, String urlPattern) {
		for (Header header : proxyResponse.getAllHeaders())
			if (filter != null) {
				if (!filter.apply(header))
					copyResponseHeader(servletRequest, servletResponse, targetUri, header, withRequestPathInfo, urlPattern);
			} else
				copyResponseHeader(servletRequest, servletResponse, targetUri, header, withRequestPathInfo, urlPattern);
	}

	/**
	 * Copy a proxied response header back to the servlet client. This is easily
	 * overwritten to filter out certain headers if desired.
	 */
	protected void copyResponseHeader(HttpServletRequest servletRequest, HttpServletResponse servletResponse,
			String targetUri, Header header, boolean withRequestPathInfo, String urlPattern) {
		logger().debug("HTTPProxyClient::Response: " + header.getName() + ":" + header.getValue());
		String headerName = header.getName();
		if (hopByHopHeaders.containsHeader(headerName))
			return;
		String headerValue = header.getValue();
		if (headerName.equalsIgnoreCase(org.apache.http.cookie.SM.SET_COOKIE)
				|| headerName.equalsIgnoreCase(org.apache.http.cookie.SM.SET_COOKIE2)) {
			copyProxyCookie(servletRequest, servletResponse, headerValue);
		} else if (headerName.equalsIgnoreCase(HttpHeaders.LOCATION)) {
			// LOCATION Header may have to be rewritten.
			servletResponse.addHeader(headerName, rewriteUrlFromResponse(servletRequest, targetUri, headerValue, withRequestPathInfo, urlPattern));
		} else {
			servletResponse.addHeader(headerName, headerValue);
		}
	}
	
	/**
	 * Copy cookie from the proxy to the servlet client. Replaces cookie path to
	 * local path and renames cookie to avoid collisions.
	 */
	protected void copyProxyCookie(HttpServletRequest servletRequest, HttpServletResponse servletResponse,
			String headerValue) {
		List<HttpCookie> cookies = HttpCookie.parse(headerValue);
		String path = "";
		if (!doPreserveCookiesContextPath)
			path = servletRequest.getContextPath(); // path starts with / or
														// is empty string
		if (!doPreserveCookiesServletPath)
			path += servletRequest.getServletPath(); // servlet path starts with
														// /
														// or is empty string
		if (path.isEmpty()) {
			path = "/";
		}

		for (HttpCookie cookie : cookies) {
			if (cookieFilterResponse != null && cookieFilterResponse.apply(cookie))
				continue;

			// set cookie name prefixed w/ a proxy value so it won't collide w/
			// other cookies
			String proxyCookieName = doPreserveCookies ? cookie.getName()
					: getCookieNamePrefix(servletName, cookie.getName()) + cookie.getName();
			Cookie servletCookie = new Cookie(proxyCookieName, cookie.getValue());
			servletCookie.setComment(cookie.getComment());
			servletCookie.setMaxAge((int) cookie.getMaxAge());
			servletCookie.setPath(path); // set to the path of the proxy servlet
			// don't set cookie domain
			servletCookie.setSecure(cookie.getSecure());
			servletCookie.setVersion(cookie.getVersion());
			servletResponse.addCookie(servletCookie);
		}
	}
	
	/**
	 * HttpClient v4.1 doesn't have the
	 * {@link org.apache.http.util.EntityUtils#consumeQuietly(org.apache.http.HttpEntity)}
	 * method.
	 */
	protected void consumeQuietly(HttpEntity entity) {
		try {
			EntityUtils.consume(entity);
		} catch (IOException e) {// ignore
			logger().info(e.getMessage(), e);
		}
	}
}
