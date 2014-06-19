/*******************************************************************************
 *     Cloud Foundry
 *     Copyright (c) [2009-2014] Pivotal Software, Inc. All Rights Reserved.
 *
 *     This product is licensed to you under the Apache License, Version 2.0 (the "License").
 *     You may not use this product except in compliance with the License.
 *
 *     This product includes a number of subcomponents with
 *     separate copyright notices and license terms. Your use of these
 *     subcomponents is subject to the terms and conditions of the
 *     subcomponent's license, as noted in the LICENSE file.
 *******************************************************************************/
package org.cloudfoundry.identity.uaa.mock.ldap;

import org.cloudfoundry.identity.uaa.authentication.Origin;
import org.cloudfoundry.identity.uaa.config.YamlServletProfileInitializer;
import org.cloudfoundry.identity.uaa.test.DefaultIntegrationTestConfig;
import org.cloudfoundry.identity.uaa.test.TestClient;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.ldap.server.ApacheDSContainer;
import org.springframework.security.oauth2.common.util.RandomValueStringGenerator;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.hamcrest.collection.IsArrayContainingInAnyOrder.arrayContainingInAnyOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.springframework.http.MediaType.TEXT_HTML_VALUE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@RunWith(Parameterized.class)
public class LdapMockMvcTests {

    @Parameters
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
            {"ldap-simple-bind.xml", "ldap-groups-null.xml"},
            {"ldap-simple-bind.xml", "ldap-groups-as-scopes.xml"},
            {"ldap-search-and-bind.xml", "ldap-groups-null.xml"},
            {"ldap-search-and-bind.xml", "ldap-groups-as-scopes.xml"},
            {"ldap-search-and-compare.xml", "ldap-groups-null.xml"},
            {"ldap-search-and-compare.xml", "ldap-groups-as-scopes.xml"}
        });
    }

    private static ApacheDSContainer apacheDS;
    private static File tmpDir;
    @BeforeClass
    public static void startApacheDS() throws Exception {
        tmpDir = new File(System.getProperty("java.io.tmpdir")+"/apacheds/"+new RandomValueStringGenerator().generate());
        tmpDir.deleteOnExit();
        System.out.println(tmpDir);
        System.setProperty("ldap.base.url","ldap://localhost:33389");
        apacheDS = new ApacheDSContainer("dc=test,dc=com","classpath:ldap_init.ldif");
        apacheDS.setWorkingDirectory(tmpDir);
        apacheDS.setPort(33389);
        apacheDS.afterPropertiesSet();
        apacheDS.start();
    }

    @AfterClass
    public static void stopApacheDS() {
        apacheDS.stop();

    }



    AnnotationConfigWebApplicationContext webApplicationContext;

    MockMvc mockMvc;
    TestClient testClient;
    JdbcTemplate jdbcTemplate;

    private String ldapProfile;
    private String ldapGroup;

    public LdapMockMvcTests(String ldapProfile, String ldapGroup) {
        this.ldapGroup = ldapGroup;
        this.ldapProfile = ldapProfile;
    }

    @Before
    public void setUp() throws Exception {
        System.setProperty("ldap.profile.file", "ldap/"+ldapProfile);
        System.setProperty("ldap.profile.groups.file", "ldap/"+ldapGroup);
        System.setProperty("ldap.group.maxSearchDepth", "10");

        webApplicationContext = new AnnotationConfigWebApplicationContext();
        webApplicationContext.setServletContext(new MockServletContext());
        webApplicationContext.register(DefaultIntegrationTestConfig.class);
        new YamlServletProfileInitializer().initialize(webApplicationContext);
        webApplicationContext.refresh();
        webApplicationContext.registerShutdownHook();

        List<String> profiles = Arrays.asList(webApplicationContext.getEnvironment().getActiveProfiles());
        Assume.assumeTrue(profiles.contains("ldap"));

        //we need to reinitialize the context if we change the ldap.profile.file property
        FilterChainProxy springSecurityFilterChain = webApplicationContext.getBean("springSecurityFilterChain", FilterChainProxy.class);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).addFilter(springSecurityFilterChain)
                .build();
        testClient = new TestClient(mockMvc);
        jdbcTemplate = webApplicationContext.getBean(JdbcTemplate.class);
    }

    @After
    public void tearDown() throws Exception {
        System.clearProperty("ldap.profile.file");
        webApplicationContext.destroy();
    }

    @Test
    public void printProfileType() {
        assertEquals(ldapProfile, webApplicationContext.getBean("testLdapProfile"));
    }

    @Test
    public void testLogin() throws Exception {


        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"))
                .andExpect(model().attributeDoesNotExist("saml"));

        mockMvc.perform(post("/login.do").accept(TEXT_HTML_VALUE)
                        .param("username", "marissa")
                        .param("password", "koaladsada"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login?error=true"));

        mockMvc.perform(post("/login.do").accept(TEXT_HTML_VALUE)
                        .param("username", "marissa2")
                        .param("password", "ldap"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    public void testAuthenticate() throws Exception {
        String username = "marissa3";
        String password = "ldap3";

        MockHttpServletRequestBuilder post =
            post("/authenticate")
            .accept(MediaType.APPLICATION_JSON)
            .param("username", username)
            .param("password", password);

        MvcResult result = mockMvc.perform(post)
            .andExpect(status().isOk())
            .andReturn();

        assertEquals("{\"username\":\"" + username + "\"}", result.getResponse().getContentAsString());
    }

    @Test
    public void testAuthenticateFailure() throws Exception {
        String username = "marissa3";
        String password = "ldapsadadasas";

        MockHttpServletRequestBuilder post =
            post("/authenticate")
                .accept(MediaType.APPLICATION_JSON)
                .param("username",username)
                .param("password",password);

        mockMvc.perform(post)
            .andExpect(status().isUnauthorized());

    }

    @Test
    public void validateOriginForNonLdapUser() throws Exception {
        String username = "marissa";
        String password = "koala";

        MockHttpServletRequestBuilder post =
            post("/authenticate")
                .accept(MediaType.APPLICATION_JSON)
                .param("username", username)
                .param("password", password);

        MvcResult result = mockMvc.perform(post)
            .andExpect(status().isOk())
            .andReturn();

        assertEquals("{\"username\":\"" + username + "\"}", result.getResponse().getContentAsString());

        String origin = jdbcTemplate.queryForObject("select origin from users where username='marissa'", String.class);
        assertEquals(Origin.UAA, origin);
    }

    @Test
    public void validateOriginForLdapUser() throws Exception {
        String username = "marissa3";
        String password = "ldap3";

        MockHttpServletRequestBuilder post =
            post("/authenticate")
                .accept(MediaType.APPLICATION_JSON)
                .param("username", username)
                .param("password", password);

        MvcResult result = mockMvc.perform(post)
            .andExpect(status().isOk())
            .andReturn();

        assertEquals("{\"username\":\"" + username + "\"}", result.getResponse().getContentAsString());

        String origin = jdbcTemplate.queryForObject("select origin from users where username='marissa3'", String.class);
        assertEquals("ldap", origin);
    }

    @Test
    public void testLdapScopes() throws Exception {
        Assume.assumeTrue(ldapGroup.equals("ldap-groups-as-scopes.xml"));
        AuthenticationManager manager = (AuthenticationManager)webApplicationContext.getBean("ldapAuthenticationManager");
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken("marissa3","ldap3");
        Authentication auth = manager.authenticate(token);
        assertNotNull(auth);
        String[] list = new String[]{
            "uaa.admin",
            "cloud_controller.read"
        };
        assertThat(list, arrayContainingInAnyOrder(getAuthorities(auth.getAuthorities())));
    }

    @Test
    public void testLdapScopesFromChainedAuth() throws Exception {
        Assume.assumeTrue(ldapGroup.equals("ldap-groups-as-scopes.xml"));
        AuthenticationManager manager = (AuthenticationManager)webApplicationContext.getBean("authzAuthenticationMgr");
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken("marissa3","ldap3");
        Authentication auth = manager.authenticate(token);
        assertNotNull(auth);
        String[] list = new String[]{
            "uaa.admin",
            "password.write",
            "scim.userids",
            "approvals.me",
            "cloud_controller.write",
            "scim.me",
            "cloud_controller_service_permissions.read",
            "openid",
            "oauth.approvals",
            "uaa.user",
            "cloud_controller.read"
        };
        assertThat(list, arrayContainingInAnyOrder(getAuthorities(auth.getAuthorities())));
    }


    @Test
    public void testNestedLdapScopes() throws Exception {
        Assume.assumeTrue(ldapGroup.equals("ldap-groups-as-scopes.xml"));
        AuthenticationManager manager = (AuthenticationManager)webApplicationContext.getBean("ldapAuthenticationManager");
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken("marissa4","ldap4");
        Authentication auth = manager.authenticate(token);
        assertNotNull(auth);
        String[] list = new String[] {
                "test.read",
                "test.write",
                "test.everything",
            };
        assertThat(list, arrayContainingInAnyOrder(getAuthorities(auth.getAuthorities())));
    }


    public String[] getAuthorities(Collection<? extends GrantedAuthority> authorities) {
        String[] result = new String[authorities!=null?authorities.size():0];
        if (result.length>0) {
            int index=0;
            for (GrantedAuthority a : authorities) {
                result[index++] = a.getAuthority();
            }
        }
        return result;
    }
}
