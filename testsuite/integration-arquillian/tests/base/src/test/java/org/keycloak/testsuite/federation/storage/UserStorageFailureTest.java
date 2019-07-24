/*
 * Copyright 2017 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.keycloak.testsuite.federation.storage;

import org.jboss.arquillian.container.test.api.ContainerController;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.graphene.page.Page;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.common.constants.ServiceAccountConstants;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.component.ComponentModel;
import org.keycloak.events.Details;
import org.keycloak.models.ClientModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.cache.CachedUserModel;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.RefreshToken;
import org.keycloak.representations.idm.ComponentRepresentation;
import org.keycloak.representations.idm.EventRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.services.managers.RealmManager;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.UserStorageProviderModel;
import org.keycloak.testsuite.AbstractAuthTest;
import org.keycloak.testsuite.AbstractTestRealmKeycloakTest;
import org.keycloak.testsuite.AssertEvents;
import org.keycloak.testsuite.auth.page.AuthRealm;
import org.keycloak.testsuite.federation.FailableHardcodedStorageProvider;
import org.keycloak.testsuite.federation.FailableHardcodedStorageProviderFactory;
import org.keycloak.testsuite.pages.AccountApplicationsPage;
import org.keycloak.testsuite.pages.AppPage;
import org.keycloak.testsuite.pages.LoginPage;
import org.keycloak.testsuite.runonserver.RunOnServerDeployment;
import org.keycloak.testsuite.util.Matchers;
import org.keycloak.testsuite.util.OAuthClient;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class UserStorageFailureTest extends AbstractTestRealmKeycloakTest {

    private static boolean initialized = false;

    @Deployment
    public static WebArchive deploy() {
        return RunOnServerDeployment.create(ComponentExportImportTest.class, AbstractAuthTest.class, RealmResource.class)
                .addPackages(true, "org.keycloak.testsuite");
    }

    private static final String LOCAL_USER = "localUser";

    private String failureProviderId;

    @ArquillianResource
    protected ContainerController controller;

    @Page
    protected LoginPage loginPage;

    @Page
    protected AppPage appPage;

    @Rule
    public AssertEvents events = new AssertEvents(this);

    @Override
    public void configureTestRealm(RealmRepresentation testRealm) {
    }


    @Before
    public void addProvidersBeforeTest() throws URISyntaxException, IOException {
        ComponentRepresentation memProvider = new ComponentRepresentation();
        memProvider.setName("failure");
        memProvider.setProviderId(FailableHardcodedStorageProviderFactory.PROVIDER_ID);
        memProvider.setProviderType(UserStorageProvider.class.getName());
        memProvider.setConfig(new MultivaluedHashMap<>());
        memProvider.getConfig().putSingle("priority", Integer.toString(0));
        failureProviderId = addComponent(memProvider);

        if (initialized) return;

        testingClient.server().run(session -> {
            RealmManager manager = new RealmManager(session);
            RealmModel appRealm = manager.getRealmByName(AuthRealm.TEST);

            ClientModel offlineClient = appRealm.addClient("offline-client");
            offlineClient.setProtocol(OIDCLoginProtocol.LOGIN_PROTOCOL);
            offlineClient.setEnabled(true);
            offlineClient.setDirectAccessGrantsEnabled(true);
            offlineClient.setSecret("secret");
            HashSet<String> redirects = new HashSet<>();
            redirects.add(OAuthClient.AUTH_SERVER_ROOT + "/offline-client");
            offlineClient.setRedirectUris(redirects);
            offlineClient.setServiceAccountsEnabled(true);
            offlineClient.setFullScopeAllowed(true);

            UserModel serviceAccount = manager.getSession().users().addUser(appRealm, ServiceAccountConstants.SERVICE_ACCOUNT_USER_PREFIX + offlineClient.getClientId());
            serviceAccount.setEnabled(true);
            RoleModel role = appRealm.getRole("offline_access");
            Assert.assertNotNull(role);
            serviceAccount.grantRole(role);
            serviceAccount.setServiceAccountClientLink(offlineClient.getClientId());

            UserModel localUser = manager.getSession().userLocalStorage().addUser(appRealm, LOCAL_USER);
            localUser.setEnabled(true);
        });

        initialized = true;
    }


    public RealmResource testRealmResource() {
        return adminClient.realm(AuthRealm.TEST);
    }

    private String addComponent(ComponentRepresentation component) {
        return UserStorageTest.addComponent(testRealmResource(), getCleanup(), component);
    }

    // this is a hack so that UserModel doesn't have to be available when offline token is imported.
    // see related JIRA - KEYCLOAK-5350 and corresponding test

    /**
     *  KEYCLOAK-5350
     */
    @Test
    public void testKeycloak5350() throws Exception {
        oauth.scope(OAuth2Constants.OFFLINE_ACCESS);
        oauth.clientId("offline-client");
        oauth.redirectUri(OAuthClient.AUTH_SERVER_ROOT + "/offline-client");
        oauth.doLogin(FailableHardcodedStorageProvider.username, "password");

        EventRepresentation loginEvent = events.expectLogin()
                .user(AssertEvents.isUUID())
                .client("offline-client")
                .detail(Details.REDIRECT_URI, OAuthClient.AUTH_SERVER_ROOT + "/offline-client")
                .assertEvent();

        final String sessionId = loginEvent.getSessionId();
        String codeId = loginEvent.getDetails().get(Details.CODE_ID);

        String code = oauth.getCurrentQuery().get(OAuth2Constants.CODE);

        OAuthClient.AccessTokenResponse tokenResponse = oauth.doAccessTokenRequest(code, "secret");
        AccessToken token = oauth.verifyToken(tokenResponse.getAccessToken());
        String offlineTokenString = tokenResponse.getRefreshToken();
        RefreshToken offlineToken = oauth.parseRefreshToken(offlineTokenString);
        events.clear();

        evictUser(FailableHardcodedStorageProvider.username);

        toggleForceFail(true);

        // make sure failure is turned on
        testingClient.server().run(session -> {
            RealmModel realm = session.realms().getRealmByName(AuthRealm.TEST);
            try {
                UserModel user = session.users().getUserByUsername(FailableHardcodedStorageProvider.username, realm);
                Assert.fail();
            } catch (Exception e) {
                Assert.assertEquals("FORCED FAILURE", e.getMessage());

            }
        });

        controller.stop(suiteContext.getAuthServerInfo().getQualifier());
        controller.start(suiteContext.getAuthServerInfo().getQualifier());
        reconnectAdminClient();

        toggleForceFail(false);


        // test that once user storage provider is available again we can still access the token.
        tokenResponse = oauth.doRefreshTokenRequest(offlineTokenString, "secret");
        Assert.assertNotNull(tokenResponse.getAccessToken());
        token = oauth.verifyToken(tokenResponse.getAccessToken());
        offlineTokenString = tokenResponse.getRefreshToken();
        offlineToken = oauth.parseRefreshToken(offlineTokenString);
        events.clear();


    }

    protected void evictUser(final String username) {
        testingClient.server().run(session -> {
            RealmModel realm = session.realms().getRealmByName(AuthRealm.TEST);
            UserModel user = session.users().getUserByUsername(username, realm);
            session.userCache().evict(realm, user);
        });
    }

    protected void toggleForceFail(final boolean toggle) {
        final String failureProviderId = this.failureProviderId;

        testingClient.server().run(session -> {
            RealmModel realm = session.realms().getRealmByName(AuthRealm.TEST);
            ComponentModel memoryProvider = realm.getComponent(failureProviderId);
            memoryProvider.getConfig().putSingle("fail", Boolean.toString(toggle));
            realm.updateComponent(memoryProvider);
        });
    }

    protected void toggleProviderEnabled(final boolean toggle) {
        final String failureProviderId = this.failureProviderId;

        testingClient.server().run(session -> {
            RealmModel realm = session.realms().getRealmByName(AuthRealm.TEST);
            ComponentModel memoryProvider = realm.getComponent(failureProviderId);
            UserStorageProviderModel model = new UserStorageProviderModel(memoryProvider);
            model.setEnabled(toggle);
            realm.updateComponent(model);
        });
    }

    private void loginSuccessAndLogout(String username, String password) {
        loginPage.open();
        loginPage.login(username, password);
        System.out.println(driver.getCurrentUrl());
        System.out.println(driver.getPageSource());
        Assert.assertTrue(appPage.isCurrent());
        Assert.assertEquals(AppPage.RequestType.AUTH_RESPONSE, appPage.getRequestType());
        Assert.assertNotNull(oauth.getCurrentQuery().get(OAuth2Constants.CODE));
        oauth.openLogout();
    }

    @Test
    public void testKeycloak5926() {
        oauth.clientId("test-app");
        oauth.redirectUri(OAuthClient.APP_AUTH_ROOT);

        // make sure local copy is deleted
        testingClient.server().run(session -> {
            RealmModel realm = session.realms().getRealmByName(AuthRealm.TEST);

            UserModel user = session.userLocalStorage().getUserByUsername(FailableHardcodedStorageProvider.username, realm);
            if (user != null) {
                session.userLocalStorage().removeUser(realm, user);
            }
        });

        // query user to make sure its imported
        testingClient.server().run(session -> {
            RealmModel realm = session.realms().getRealmByName(AuthRealm.TEST);

            UserModel user = session.users().getUserByUsername(FailableHardcodedStorageProvider.username, realm);
            Assert.assertNotNull(user);

        });


        evictUser(FailableHardcodedStorageProvider.username);
        evictUser(LOCAL_USER);

        toggleForceFail(true);

        testingClient.server().run(session -> {
            RealmModel realm = session.realms().getRealmByName(AuthRealm.TEST);

            UserModel local = session.users().getUserByUsername(LOCAL_USER, realm);
            Assert.assertNotNull(local);
            // assert that lookup of user storage user fails
            try {
                UserModel user = session.users().getUserByUsername(FailableHardcodedStorageProvider.username, realm);
                Assert.fail();
            } catch (Exception e) {
                Assert.assertEquals("FORCED FAILURE", e.getMessage());

            }

        });

        // test that we can still login to a user
        loginSuccessAndLogout("test-user@localhost", "password");

        toggleProviderEnabled(false);

        testingClient.server().run(session -> {
            RealmModel realm = session.realms().getRealmByName(AuthRealm.TEST);

            UserModel local = session.users().getUserByUsername(LOCAL_USER, realm);
            Assert.assertNotNull(local);
            List<UserModel> result;
            result = session.users().searchForUser(LOCAL_USER, realm);
            Assert.assertEquals(1, result.size());
            session.users().searchForUser(FailableHardcodedStorageProvider.username, realm);
            Assert.assertEquals(1, result.size());
            session.users().searchForUser(LOCAL_USER, realm, 0, 2);
            Assert.assertEquals(1, result.size());
            session.users().searchForUser(FailableHardcodedStorageProvider.username, realm, 0, 2);
            Assert.assertEquals(1, result.size());
            Map<String, String> localParam = new HashMap<>();
            localParam.put("username", LOCAL_USER);
            Map<String, String> hardcodedParam = new HashMap<>();
            hardcodedParam.put("username", FailableHardcodedStorageProvider.username);

            result = session.users().searchForUser(localParam, realm);
            Assert.assertEquals(1, result.size());
            session.users().searchForUser(hardcodedParam, realm);
            Assert.assertEquals(1, result.size());
            session.users().searchForUser(localParam, realm, 0, 2);
            Assert.assertEquals(1, result.size());
            session.users().searchForUser(hardcodedParam, realm, 0, 2);
            Assert.assertEquals(1, result.size());

            session.users().getUsers(realm);
            session.users().getUsersCount(realm);



            UserModel user = session.users().getUserByUsername(FailableHardcodedStorageProvider.username, realm);
            Assert.assertFalse(user instanceof CachedUserModel);
            Assert.assertEquals(FailableHardcodedStorageProvider.username, user.getUsername());
            Assert.assertEquals(FailableHardcodedStorageProvider.email, user.getEmail());
            Assert.assertFalse(user.isEnabled());
            try {
                user.setEmail("error@error.com");
                Assert.fail();
            } catch (Exception ex) {

            }
        });

        // make sure user isn't cached as provider is disabled
        testingClient.server().run(session -> {
            RealmModel realm = session.realms().getRealmByName(AuthRealm.TEST);

            UserModel user = session.users().getUserByUsername(FailableHardcodedStorageProvider.username, realm);
            Assert.assertFalse(user instanceof CachedUserModel);
            Assert.assertEquals(FailableHardcodedStorageProvider.username, user.getUsername());
            Assert.assertEquals(FailableHardcodedStorageProvider.email, user.getEmail());
        });

        // make ABSOLUTELY sure user isn't cached as provider is disabled
        testingClient.server().run(session -> {
            RealmModel realm = session.realms().getRealmByName(AuthRealm.TEST);

            UserModel user = session.users().getUserByUsername(FailableHardcodedStorageProvider.username, realm);
            Assert.assertFalse(user instanceof CachedUserModel);
            Assert.assertEquals(FailableHardcodedStorageProvider.username, user.getUsername());
            Assert.assertEquals(FailableHardcodedStorageProvider.email, user.getEmail());
        });



        toggleProviderEnabled(true);
        toggleForceFail(false);

        // user should be cachable now
        testingClient.server().run(session -> {
            RealmModel realm = session.realms().getRealmByName(AuthRealm.TEST);

            UserModel user = session.users().getUserByUsername(FailableHardcodedStorageProvider.username, realm);
            Assert.assertTrue(user instanceof CachedUserModel);
            Assert.assertEquals(FailableHardcodedStorageProvider.username, user.getUsername());
            Assert.assertEquals(FailableHardcodedStorageProvider.email, user.getEmail());
        });

        events.clear();
    }


    //@Test
    public void testIDE() throws Exception {
        Thread.sleep(100000000);
    }

}
