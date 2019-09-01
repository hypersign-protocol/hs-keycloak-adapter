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

package io.hypermine.hypersign.api;

import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.ServicesLogger;
import org.keycloak.models.*;
import java.util.HashMap; 
import java.util.Map;
import java.util.UUID;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import org.json.JSONObject;
import org.keycloak.wildfly.adduser.*;
import org.codehaus.jackson.map.ObjectMapper;
import io.hypermine.hypersign.service.AuthServerCaller;

/**
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
public class HSResourceProvider implements RealmResourceProvider {

    class HSUserModel {
        String challange;
        String userId;
        boolean hasLoggedIn;
        public HSUserModel(String challange, String userId, boolean hasLoggedIn){
            this.challange = challange;
            this.userId = userId;
            this.hasLoggedIn = hasLoggedIn;
        }
    }

    class FResponse{
        public String status;
        public String data;
        public FResponse(){}
        public FResponse(String status, String data){
            this.status = status;
            this.data = data;
        }
    }
    
    enum Status {
        SUCCESS,
        FAIL
    }

    private static ServicesLogger logger = ServicesLogger.LOGGER;
    private static HashMap<String, HSUserModel> userSessionMap = new HashMap<>();// this is temporary
    private KeycloakSession session;
    
    public HSResourceProvider(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public Object getResource() {
        return this;
    }

    @GET
    @Produces("text/plain; charset=utf-8")
    public String get() {
        logger.info("This is hypersign authenticator api call");
        String name = session.getContext().getRealm().getDisplayName();
        if (name == null) {
            name = session.getContext().getRealm().getName();
        }
        return "Hello " + name;
    }

    @POST
    @Path("register")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public String register(String body) {
        logger.info("Register api called!");
        JSONObject json = null;
        String publicKey = "";
        String emaiid = "";
        String username = "";
        String companyid = "";
        UserModel newuser = null;
    	try{
            if(!body.isEmpty()){
                json = new JSONObject(body);
                if(json != null){
                    publicKey = json.getString("publickey");
                    emaiid = json.getString("email");
                    username = json.getString("username");
                    companyid = json.getString("companyid");
                    if(!publicKey.isEmpty() || !emaiid.isEmpty()) {   
                        //saving the user in hs-auth-server                     
                        String url = "http://localhost:3000/register";
                        String responseFromAuthServer = AuthServerCaller.postApiCall(url,body);
                        json = new JSONObject(responseFromAuthServer);
                        if(json.getInt("status") == 0){                            
                            throw new Exception(json.getString("message"));                 
                        }else{
                            // I had to trim the publickey to accomodate it in ID field (which is of size 36) in db
                            publicKey = publicKey.substring(0, publicKey.length() - 6);
                            // saving the user in keycloak
                            newuser = this.session != null && this.session.userLocalStorage() != null
                            ? this.session.userLocalStorage().addUser(this.session.getContext().getRealm(),publicKey,emaiid,true, true)
                            : null;
                            if(newuser != null){
                                return this.formattedReponse(Status.SUCCESS, json.getString("message"));
                            }else{
                                throw new Exception("Could not create the user");                               
                            }
                        }
                    }else {
                        throw new Exception("Publickey or emailId is null");
                    }            
                }else{
                    throw new Exception("Could not parse the body");
                }
            }else {
                throw new Exception("Request body is null");
            }
        }catch(Exception e){
            return this.formattedReponse(Status.FAIL,e.toString());            
        }    
    }

    @POST
    @Path("sign")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public String sign(String body) {
        logger.info("sign api called!");
        JSONObject bodyObj = null;
        String sessionId = "";
        String challange = "";
        String publickey = "";
        String signature = "";
        String companyId = "";
        String rawMessage = "";
        HSUserModel user = null;
    	try{
            if(!body.isEmpty()){
                bodyObj = new JSONObject(body);
                if(bodyObj != null){
                    publickey = bodyObj.getString("publicKey"); sessionId = bodyObj.getString("ksSessionId");
                    challange = bodyObj.getString("challenge"); signature = bodyObj.getString("signedRsv");
                    companyId = bodyObj.getString("companyId"); rawMessage = bodyObj.getString("rawMsg");
                    if(!publickey.isEmpty() || 
                        !sessionId.isEmpty() || 
                        !signature.isEmpty() || 
                        !challange.isEmpty() || 
                        !companyId.isEmpty()) {
                            // String requestBody = "{\"companyId\": \"$COMPANYID\", \"publicKey\": \"$PUBLICKEY\", \"signedRsv\": \"$SIGNEDRSV\",\"rawMsg\": \"$RAWMESSAGE\", \"challenge\" : \"$SESSIONID\", \"ksSessionId\" : \"$KSSESSIONID\"}";
                            // requestBody.replace("$COMPANYID", companyId);
                            // requestBody.replace("$PUBLICKEY", publickey);
                            // requestBody.replace("$SIGNEDRSV", signature);
                            // requestBody.replace("$SESSIONID", challange);
                            // requestBody.replace("$RAWMESSAGE", rawMessage);
                            // requestBody.replace("$KSSESSIONID", sessionId);
                            if(isSignatureValid(body)){
                                if (userSessionMap.containsKey(sessionId)){
                                    user = new HSUserModel(challange, publickey, true);
                                    userSessionMap.put(sessionId, user);
                                    return this.formattedReponse(Status.SUCCESS, "User authenticated");    
                                }else{
                                    throw new Exception("Invalid session");                                   
                                }
                            }else{
                                throw new Exception("Invalid signature");                               
                            }   
                    }else {
                        throw new Exception("Publickey, sessionId or signature is null");
                    }            
                }else{
                    throw new Exception("Could not parse the body");
                }
            }else {
                throw new Exception("Request body is null");
            }
        }catch(Exception e){
            return this.formattedReponse(Status.FAIL,e.toString());            
        }    
    }

    @POST
    @Path("session")
    @Produces(MediaType.APPLICATION_JSON)
    public String getNewSession(String body) {
        logger.info("session api called!");
        JSONObject json = null;
        try{
            if(!body.isEmpty()){
                if(userSessionMap != null){
                    json = new JSONObject(body);
                    String ksSessionId = json.getString("kcSessionId");
                    String companyId = json.getString("companyId");
                    if(json != null && !ksSessionId.isEmpty()){
                        // call auth-server for session/challenge
                        String url = "http://localhost:3000/challenge";
                        String reqstBody = "{\"kcSessionId\" : \""+ksSessionId+"\", \"companyId\":\""+companyId+"\"}";
                        String response = AuthServerCaller.postApiCall(url, reqstBody);
                        json = new JSONObject(response);
                        if(json != null && json.getInt("status") == 1){
                            // json = new JSONObject(json.get("data"));
                            userSessionMap.put(ksSessionId, null); // this is kc session                    
                            return json.getString("data"); //this.formattedReponse(Status.SUCCESS, sessionId);    
                        }else{
                            throw new Exception(json.getString("message"));
                        }
                    }else{
                        throw new Exception("Keycloak sessionid can not be null");
                    }
                    
                }else{
                    throw new Exception("userSessionMap is null");
                }
            }else{
                throw new Exception("Request body can not be null");
            }
            
        }catch(Exception e){
            return this.formattedReponse(Status.FAIL,e.toString());            
        }
    }

    @GET
    @Path("listen/success/{sessionId}")
    @Produces(MediaType.APPLICATION_JSON)
    public String listenSuccess(@PathParam("sessionId") String sessionId) {
        logger.info("listen/success api called!");
        try{
            if(userSessionMap != null){
                if (userSessionMap.containsKey(sessionId) && userSessionMap.get(sessionId) != null){
                    HSUserModel user = userSessionMap.get(sessionId);
                    if (user != null && user.hasLoggedIn){
                        return user.userId; //this.formattedReponse(Status.SUCCESS, user.userId); 
                    }else{
                        throw new Exception("User not found or not validated");      
                    }
                }else{
                    throw new Exception("Invalid session or user has not yet validated");  
                }
            }else{
                throw new Exception("userSessionMap is null");
            }
        }catch(Exception e){
            return this.formattedReponse(Status.FAIL,e.toString());            
        } 
    }

    @GET
    @Path("listen/fail/{sessionId}")
    @Produces(MediaType.APPLICATION_JSON)
    public String listenFail(@PathParam("sessionId") String sessionId) { 
        logger.info("listen/fail api called!");
        try{
            if(userSessionMap != null){
                if (userSessionMap.containsKey(sessionId)){
                    userSessionMap.remove(sessionId);
                    return this.formattedReponse(Status.SUCCESS, "Session deleted");    
                }else{
                    throw new Exception("Invalid session");  
                }
            }else{
                throw new Exception("userSessionMap is null");
            }
        }catch(Exception e){
            return this.formattedReponse(Status.FAIL,e.toString());            
        } 
    }

    private String formattedReponse(Status status, String data){
        String respStr = "";
        try{
            FResponse response  = new FResponse();
            response.status = status.name();
            response.data = data;
            ObjectMapper Obj = new ObjectMapper();
            // JSONObject bodyObj = new JSONObject(response);
            respStr = Obj.writeValueAsString(response);            
        }
        catch(Exception e){
            respStr = e.toString();
        }   
        return respStr;
    }
    
    private Boolean isSignatureValid(String body){
        try{
            // call auth-server to validate this user.
            String url = "http://localhost:3000/verify";
            String responseFromAuthServer = AuthServerCaller.postApiCall(url,body);
            JSONObject json = new JSONObject(responseFromAuthServer);
            if(json != null  && json.getInt("status") == 1){
                return true;
            }else{
                return false;
            }
        }catch(Exception e){
            return false;
        }
    }

    @Override
    public void close() {
    }

}
