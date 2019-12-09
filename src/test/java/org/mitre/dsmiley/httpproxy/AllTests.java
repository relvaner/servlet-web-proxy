package org.mitre.dsmiley.httpproxy;

import org.junit.runners.Suite;
import org.junit.runner.RunWith;

@RunWith(Suite.class)
@Suite.SuiteClasses({
	ProxyServletTest.class,
	ModifyHeadersProxyServletTest.class,
	URITemplateProxyServletTest.class
})
public class AllTests {

}
