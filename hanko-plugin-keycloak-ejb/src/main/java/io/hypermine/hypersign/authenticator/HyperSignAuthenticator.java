/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
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

package io.hypermine.hypersign.authenticator;

import org.jboss.resteasy.spi.HttpResponse;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.common.util.ServerCookie;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserCredentialModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.ServicesLogger;

import javax.ws.rs.core.Cookie;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import java.net.URI;

import io.hypermine.hypersign.service.AuthServerCaller;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class HyperSignAuthenticator implements Authenticator {

    public static final String CREDENTIAL_TYPE = "hypersign_qrcode";
    private static ServicesLogger logger = ServicesLogger.LOGGER;

    protected boolean hasCookie(AuthenticationFlowContext context) {
        Cookie cookie = context.getHttpRequest().getHttpHeaders().getCookies().get("HYPERSIGN_QRCODE_SOLVED");
        boolean result = cookie != null;
        return result;
    }

    /**********************************************************************************
     * This will check if browser has already tried solving the challenge before, if that
     * is the case then it wont again give the same challenge, rather it will bypass it
     * 
     * ********************************************************************************/
    @Override
    public void authenticate(AuthenticationFlowContext context) {
        logger.info("HyperSignAuthenticator :: authenticate : starts");
        if (hasCookie(context)) {
            context.success();
            return;
        }
        //String response = QRCodeGenerator.createORLoginPage(context.getRealm().getDisplayName());
        Response challenge = getChallenge(context);
        context.challenge(challenge);
        // System.out.println(context.getActionUrl(context.generateAccessCode()));
        context.form().setAttribute("hypersign", "This is for HyperSign testing");
        logger.info("HyperSignAuthenticator :: authenticate : ends");
    }

    protected Response getChallenge(AuthenticationFlowContext context){
        logger.info("HyperSignAuthenticator :: getChallenge : starts");
        String url = "http://localhost:8080/auth/realms/master/hypersign/session";
        // blocking call to get new session
        String newHSsession = callAPi(url);
        Response challenge = context.form().setAttribute("loginMethod", "UAF").setAttribute("hsSession",newHSsession).createForm("hypersign-new.ftl");    
        return challenge;
    }

    /**********************************************************************************
     * This is the main method that will get called once user solves the challenge and 
     * click on the submit button.
     * 
     * ********************************************************************************/
    @Override
    public void action(AuthenticationFlowContext context) {
        logger.info("HyperSignAuthenticator :: action : starts");
        MultivaluedMap<String, String> formData = context.getHttpRequest().getDecodedFormParameters();
        if (formData.containsKey("cancel")) {
            context.cancelLogin();
            return;
        }
        logger.info("HyperSignAuthenticator :: action : before validateUser call");
        boolean validated = validateUser(context);
        logger.info("HyperSignAuthenticator :: action : after validateUser call");
        if (!validated) {
            Response challenge = getChallenge(context);
            context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, challenge);
        }
        logger.info("HyperSignAuthenticator :: action : ends");
    }

    private String callAPi(String url){
        try{
            logger.info("HyperSignAuthenticator :: callApi : start");
            logger.info("HyperSignAuthenticator :: callApi : url :");
            logger.info(url);
            return AuthServerCaller.getApiCall(url);
        }catch(Exception e){
            logger.error(e);
            return "";
        }
    }

    private boolean validateUser(AuthenticationFlowContext context) {
        logger.info("HyperSignAuthenticator :: validateUser : starts ");
        MultivaluedMap<String, String> formData = context.getHttpRequest().getDecodedFormParameters();
        Boolean isValid = false;
        String url = "";
        String sessionId = formData.getFirst("sessionId");
        logger.info("HyperSignAuthenticator :: validateUser : sessionId :");
        logger.info(sessionId);
        String userIdFromForm = formData.getFirst("userId");
        logger.info("HyperSignAuthenticator :: validateUser : userId :");
        logger.info(userIdFromForm);
        // check if this user is correct from api call;
        // blocking api call
        url = "http://localhost:8080/auth/realms/master/hypersign/listen/" + "success/" + sessionId;
        String userIdFromAPi = callAPi(url);
        logger.info(userIdFromAPi);
        if(userIdFromAPi.equals(userIdFromForm)){
            logger.info("HyperSignAuthenticator :: validateUser : User is valid");

            UserCredentialModel input = new UserCredentialModel();
            // input.setType(SecretQuestionCredentialProvider.QR_CODE);
            input.setValue("secret");
        
            UserModel user = context.getSession().users().getUserById(userIdFromAPi, context.getRealm());
            context.setUser(user);
            setCookie(context);
            context.success();
            isValid = true;
        }else{
            logger.info("HyperSignAuthenticator :: validateUser : User is not valid");
            logger.info("HyperSignAuthenticator :: validateUser : Clear session of this user");
            // clear session in case of failure
            url = "http://localhost:8080/auth/realms/master/hypersign/listen/" + "fail/" +sessionId;
            callAPi(url);
            context.cancelLogin();
        }
        logger.info("HyperSignAuthenticator :: validateUser : ends ");
        return isValid;
    }
    
    /**********************************************************************************
     * Set the Hypersign cookie for 30 days.
     * 
     * ********************************************************************************/
   
    protected void setCookie(AuthenticationFlowContext context) {
        AuthenticatorConfigModel config = context.getAuthenticatorConfig();
        int maxCookieAge = 60 * 60 * 24 * 30; // 30 days
        if (config != null) {
            maxCookieAge = Integer.valueOf(config.getConfig().get("cookie.max.age"));

        }
        URI uri = context.getUriInfo().getBaseUriBuilder().path("realms").path(context.getRealm().getName()).build();
        addCookie("HYPERSIGN_QRCODE_SOLVED", "true",
                uri.getRawPath(),
                null, null,
                maxCookieAge,
                false, true);
    }

    public static void addCookie(String name, String value, String path, String domain, String comment, int maxAge, boolean secure, boolean httpOnly) {
        HttpResponse response = ResteasyProviderFactory.getContextData(HttpResponse.class);
        StringBuffer cookieBuf = new StringBuffer();
        ServerCookie.appendCookieValue(cookieBuf, 1, name, value, path, domain, comment, maxAge, secure, httpOnly);
        String cookie = cookieBuf.toString();
        response.getOutputHeaders().add(HttpHeaders.SET_COOKIE, cookie);
    }

    /**********************************************************************************
     * We are setting this as false since we dont need the user information as of now.
     * 
     * ********************************************************************************/
    @Override
    public boolean requiresUser() {
        return false;
    }

    /**********************************************************************************
     * Setting this as false sine we do not want to store any information
     * 
     * ********************************************************************************/
    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        //return false;
    	return session.userCredentialManager().isConfiguredFor(realm, user, HyperSignCredentialProvider.QR_CODE);
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
        user.addRequiredAction(HyperSignRequiredAction.PROVIDER_ID);
    }

    @Override
    public void close() {

    }
}
