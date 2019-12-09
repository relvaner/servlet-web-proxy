package servlet.web.proxy;


import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public final class TargetUriCookie {
	public static void generateCookie(HttpServletRequest request, HttpServletResponse response, String domain, String targetUri) {
		Cookie cookie = new Cookie("targetUri", targetUri);
		cookie.setDomain(domain);
		cookie.setPath(request.getContextPath());
		cookie.setSecure(false);
		cookie.setHttpOnly(true);
		cookie.setMaxAge(60*24*60*60);
	
		response.addCookie(cookie);
	}
	
	public static String get(HttpServletRequest request) {
		String result = null;
		
		Cookie[] cookies = request.getCookies();
		if (cookies!=null)
			for (int i=0; i<cookies.length; i++) 
				if (cookies[i].getName().equals("targetUri")) {
					result = cookies[i].getValue();
					break;
				}
		
		return result;
	}
	
	public static String get(HttpServletRequest request, HttpServletResponse response, String domain, String defaultLanguage) {
		String result = null;
		
		Cookie[] cookies = request.getCookies();
		boolean found = false;
		if (cookies!=null)
			for (int i=0; i<cookies.length; i++) 
				if (cookies[i].getName().equals("language")) {
					result = cookies[i].getValue();
					found = true;
					break;
				}
		
		if (!found) {
			generateCookie(request, response, domain, defaultLanguage);
			result = defaultLanguage;
		}
		
		return result;
	}
	
	public static void set(HttpServletRequest request, HttpServletResponse response, String domain, String targetUri) {
		generateCookie(request, response, domain, targetUri);
	}
}
