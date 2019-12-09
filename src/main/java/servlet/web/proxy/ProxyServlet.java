package servlet.web.proxy;

import java.io.IOException;
import java.net.URI;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.mutable.MutableBoolean;

public class ProxyServlet extends AbstractProxyServlet {
	protected static final long serialVersionUID = -6050461015977571649L;
	
	/** The parameter name for the target (destination) URI to proxy to. */
	protected static final String P_TARGET_URI = "targetUri";
	
	protected String targetUri;
	protected URI targetUriObj;
	
	@Override
	public void init() throws ServletException {
		targetUri = getConfigParam(P_TARGET_URI);
		if (targetUri == null)
			throw new ServletException(P_TARGET_URI + " is required.");
		
		targetUriObj = getTargetObj(targetUri);
		if (targetUriObj==null)
			throw new ServletException(P_TARGET_URI + " is not valid.");
		
		super.init();
	}
	
	@Override
	protected void service(HttpServletRequest servletRequest, HttpServletResponse servletResponse) throws ServletException, IOException {
		doService(servletRequest, servletResponse, targetUri, targetUriObj, servletRequest.getPathInfo(), new MutableBoolean(true), null, false, null, null);
	}
}
