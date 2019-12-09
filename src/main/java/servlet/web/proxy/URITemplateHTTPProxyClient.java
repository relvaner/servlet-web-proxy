package servlet.web.proxy;

import javax.servlet.http.HttpServletRequest;

public class URITemplateHTTPProxyClient extends HTTPProxyClient {
	protected final String ATTR_QUERY_STRING;
	
	public URITemplateHTTPProxyClient(String servletName, final String ATTR_QUERY_STRING) {
		super(servletName);
		
		this.ATTR_QUERY_STRING = ATTR_QUERY_STRING;
	}

	@Override
	protected String rewriteQueryStringFromRequest(HttpServletRequest servletRequest, String queryString) {
		return (String) servletRequest.getAttribute(ATTR_QUERY_STRING);
	}
}
