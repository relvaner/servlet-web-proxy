package servlet.web.proxy;

import static servlet.web.proxy.ProxyLogger.logger;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;

import javax.servlet.http.HttpServletRequest;

import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.config.SocketConfig;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.HttpClientBuilder;

public class AbstractHTTPProxyClient {
	protected HttpClient proxyClient;

	protected boolean doLog = false;
	protected boolean doHandleRedirects = false;
	protected boolean doPreserveHostnameVerification = false;
	protected int connectionRequestTimeout = -1;
	protected int connectTimeout = -1;
	protected int readTimeout = -1;
	protected int maxTotalConnections = 20;
	protected int maxConnectionsPerRoute = 2;
	
	public AbstractHTTPProxyClient() {
		super();
	}
	
	public boolean isDoLog() {
		return doLog;
	}

	public void setDoLog(boolean doLog) {
		this.doLog = doLog;
	}

	public boolean isDoHandleRedirects() {
		return doHandleRedirects;
	}

	public void setDoHandleRedirects(boolean doHandleRedirects) {
		this.doHandleRedirects = doHandleRedirects;
	}

	public boolean isDoPreserveHostnameVerification() {
		return doPreserveHostnameVerification;
	}

	public void setDoPreserveHostnameVerification(boolean doPreserveHostnameVerification) {
		this.doPreserveHostnameVerification = doPreserveHostnameVerification;
	}
	
	public int getConnectionRequestTimeout() {
		return connectionRequestTimeout;
	}

	public void setConnectionRequestTimeout(int connectionRequestTimeout) {
		this.connectionRequestTimeout = connectionRequestTimeout;
	}

	public int getConnectTimeout() {
		return connectTimeout;
	}

	public void setConnectTimeout(int connectTimeout) {
		this.connectTimeout = connectTimeout;
	}

	public int getReadTimeout() {
		return readTimeout;
	}

	public void setReadTimeout(int readTimeout) {
		this.readTimeout = readTimeout;
	}
	
	public int getMaxTotalConnections() {
		return maxTotalConnections;
	}

	public void setMaxTotalConnections(int maxTotalConnections) {
		this.maxTotalConnections = maxTotalConnections;
	}

	public int getMaxConnectionsPerRoute() {
		return maxConnectionsPerRoute;
	}

	public void setMaxConnectionsPerRoute(int maxConnectionsPerRoute) {
		this.maxConnectionsPerRoute = maxConnectionsPerRoute;
	}

	public void setProxyClient(HttpClient proxyClient) {
		this.proxyClient = proxyClient;
	}

	/**
	 * The http client used.
	 * 
	 * @see #createHttpClient(RequestConfig)
	 */
	public HttpClient getProxyClient() {
		return proxyClient;
	}
	
	public void init() {
		proxyClient = createHttpClient(buildRequestConfig(), buildSocketConfig());
	}

	/**
	 * Called from {@link #init(javax.servlet.ServletConfig)}. HttpClient offers
	 * many opportunities for customization. In any case, it should be
	 * thread-safe.
	 **/
	protected HttpClient createHttpClient(final RequestConfig requestConfig, final SocketConfig socketConfig) {
		HttpClient result = null;

		if (doPreserveHostnameVerification)
			result = HttpClientBuilder
						.create()
						.setDefaultRequestConfig(requestConfig)
						.setDefaultSocketConfig(socketConfig)
						.setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE)
						.setMaxConnTotal(maxTotalConnections)
						.setMaxConnPerRoute(maxConnectionsPerRoute)
						.build();
		else
			result = HttpClientBuilder
						.create()
						.setDefaultRequestConfig(requestConfig)
						.setDefaultSocketConfig(socketConfig)
						.setMaxConnTotal(maxTotalConnections)
						.setMaxConnPerRoute(maxConnectionsPerRoute)
						.build();

		return result;
	}
	
	public void destroy() {
		// Usually, clients implement Closeable:
		if (proxyClient instanceof Closeable) {
			try {
				((Closeable) proxyClient).close();
			} catch (IOException e) {
				logger().info("While destroying servlet, shutting down HttpClient: " + e, e);
			}
		} else {
			// Older releases require we do this:
			if (proxyClient != null)
				proxyClient.getConnectionManager().shutdown();
		}
	}
	
	/*
	protected void closeQuietly(Closeable closeable) {
		try {
			closeable.close();
		} catch (IOException e) {
			logger().info(e.getMessage(), e);
		}
	}
	 */
	
	/**
	 * Sub-classes can override specific behaviour of
	 * {@link org.apache.http.client.config.RequestConfig}.
	 */
	protected RequestConfig buildRequestConfig() {
		RequestConfig.Builder builder = RequestConfig.custom()
				.setRedirectsEnabled(doHandleRedirects)
				.setCookieSpec(CookieSpecs.IGNORE_COOKIES) // we handle them in the servlet instead
				.setConnectionRequestTimeout(connectionRequestTimeout)
				.setConnectTimeout(connectTimeout)
				.setSocketTimeout(readTimeout);
		return builder.build();
	}
	
	protected SocketConfig buildSocketConfig() {
		if (readTimeout<1)
			return null;
		
		return SocketConfig.custom()
			.setSoTimeout(readTimeout)
			.build();
	}
	
	// changed by David A. Bauer, servletResponse removed
	protected HttpResponse doExecute(HttpServletRequest servletRequest, HttpRequest proxyRequest, URI targetObj)
			throws IOException {
		if (doLog)
			logger().info(servletRequest.getMethod() + " uri: " + servletRequest.getRequestURI() + " -- "
					+ proxyRequest.getRequestLine().getUri());

		return proxyClient.execute(URIUtils.extractHost(targetObj), proxyRequest);
	}
}
