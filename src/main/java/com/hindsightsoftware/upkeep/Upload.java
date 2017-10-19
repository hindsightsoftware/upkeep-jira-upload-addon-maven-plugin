package com.hindsightsoftware.upkeep;

import org.apache.commons.codec.Charsets;
import org.apache.http.message.BasicNameValuePair;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;

import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mojo( name = "upload", defaultPhase = LifecyclePhase.PRE_INTEGRATION_TEST )
public class Upload extends AbstractMojo {
    @Parameter( property = "jira.addon.upload.url", defaultValue = "" )
    private String baseUrl;

    @Parameter( property = "jira.addon.file", defaultValue = "" )
    private File addonFile;

    @Parameter( property = "jira.addon.key", defaultValue = "" )
    private String addonKey;

    @Parameter( property = "jira.login.username", defaultValue = "admin" )
    private String username;

    @Parameter( property = "jira.login.password", defaultValue = "admin" )
    private String password;

    @Parameter( property = "jira.max.wait", defaultValue = "300" )
    private int maxWaitTime;

    @Parameter( property = "jira.addon.license", defaultValue = "" )
    private String addonLicense;

    @Parameter( property = "jira.addon.license.file", defaultValue = "" )
    private File addonLicenseFile;

    @Parameter
    private boolean skip;

    private Log log;

    public void setLog(Log log){
        this.log = new SystemStreamLog();
    }

    public void execute() throws MojoExecutionException {
        if(skip)return;

        if(baseUrl.indexOf("http://") != 0 && baseUrl.indexOf("https://") != 0){
            if(baseUrl.indexOf("http://") != 0){
                baseUrl = "http://" + baseUrl;
            }
            else if(baseUrl.indexOf("https://") != 0){
                baseUrl = "https://" + baseUrl;
            }
        }

        log.info("JIRA base url: " + baseUrl);
        log.info("JIRA addon key: " + addonKey);
        log.info("JIRA addon file: " + addonFile.getAbsolutePath());

        try {
            // Do login (get session cookie)
            Http.Response response = Http.POST(baseUrl + "/rest/auth/1/session")
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"username\":\"" + username + "\", \"password\":\"" + password + "\"}")
                    .timeout(30)
                    .send();

            log.info("POST \"" + baseUrl + "/rest/auth/1/session\" returned " + response.getStatusCode());
            if(response.getStatusCode() != 200){
                throw new MojoExecutionException(response.getContent());
            }

            JSONObject jsonObject = (JSONObject)new JSONParser().parse(response.getContent());
            String sessionName = (String)((JSONObject)jsonObject.get("session")).get("name");
            String sessionValue = (String)((JSONObject)jsonObject.get("session")).get("value");

            log.info("Got session name: " + sessionName);
            log.info("Got session value: " + sessionValue);

            // Web sudo authenticate
            response = Http.POST(baseUrl + "/secure/admin/WebSudoAuthenticate.jspa")
                    .withHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8")
                    .withHeader("Content-Type", "application/x-www-form-urlencoded")
                    .withHeader("Cookie", sessionName + "=" + sessionValue)
                    .withForm(Arrays.asList(new BasicNameValuePair("webSudoPassword", password)))
                    .send();

            log.info("POST \"" + baseUrl + "/secure/admin/WebSudoAuthenticate.jspa\" returned " + response.getStatusCode());
            if(response.getStatusCode() != 302){
                log.error("Expected return code 302!");
                throw new MojoExecutionException("Websudo login failed!");
            }

            String webSudoCookie = "";
            for(String value : response.getHeader("Set-Cookie")){
                int index = value.indexOf(";");
                webSudoCookie += value.substring(0, (index > 0 ? index : value.length())) + "; ";
            }
            log.info("Got websudo cookie: " + webSudoCookie);

            // Delete Addon
            response = Http.DELETE(baseUrl + "/rest/plugins/1.0/" + addonKey)
                    .withHeader("Accept", "application/json, text/javascript, */*; q=0.01")
                    .withHeader("Content-Type", "application/json")
                    .withHeader("Cookie", webSudoCookie)
                    .send();
            log.info("DELETE \"" + baseUrl + "/rest/plugins/1.0/" + addonKey + "\" returned " + response.getStatusCode());
            if(response.getStatusCode() == 404){
                log.warn("Addon: " + addonKey + " was not deleted! Reason: Not found");
            }
            else if(response.getStatusCode() != 204){
                log.error("Expected return code 204!");
                throw new MojoExecutionException("Plugin delete failed!");
            }

            // Get UPM token
            response = Http.GET(baseUrl + "/rest/plugins/1.0/")
                    .withHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8")
                    .withHeader("Upgrade-Insecure-Requests", "1")
                    .withHeader("Content-Type", "application/json")
                    .withHeader("Cookie", webSudoCookie)
                    .send();

            log.info("GET \"" + baseUrl + "/rest/plugins/1.0/\" returned " + response.getStatusCode());
            if(response.getStatusCode() != 200){
                log.error("Expected return code 200!");
                throw new MojoExecutionException("Failed to get UPM code!");
            }

            String upmToken = response.getHeader("upm-token").get(0);
            log.info("UPM code: " + upmToken);

            // Upload addon
            response = Http.POST(baseUrl + "/rest/plugins/1.0/?token=" + upmToken)
                    //.withHeader("Content-Type", "multipart/form-data")
                    .withHeader("Accept","text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8")
                    .withHeader("Cookie", webSudoCookie)
                    .withFile("plugin", addonFile)
                    .send();

            log.info("POST \"" + baseUrl + "/rest/plugins/1.0/?token=" + upmToken + "\" returned " + response.getStatusCode());
            if(response.getStatusCode() != 202){
                log.error("Expected return code 202!");
                throw new MojoExecutionException("Failed to upload addon!");
            }

            String pendingUrl = "";
            String patternStr = "\\/rest\\/plugins\\/1\\.0\\/pending\\/[a-z0-9-]*";
            Pattern pattern = Pattern.compile(patternStr);
            Matcher matcher = pattern.matcher(response.getContent());
            if(matcher.find()){
                pendingUrl += matcher.group(0);
                log.info("Pending url found: " + pendingUrl);
            } else {
                log.error("Failed to find pending URL from: " + response.getContent() + " using pattern: " + patternStr);
            }

            boolean finished = false;
            long startTime = System.currentTimeMillis();
            while(System.currentTimeMillis() - startTime < maxWaitTime * 1000){
                response = Http.GET(baseUrl + pendingUrl)
                        .withHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8")
                        .withFollowRedirects(false)
                        .withHeader("Cookie", webSudoCookie)
                        .send();
                log.info("GET \"" + baseUrl + pendingUrl + "\" returned " + response.getStatusCode());

                if(response.getStatusCode() == 303) {
                    log.info("Addon is installed!");
                    finished = true;
                    break;
                }
                else if(response.getStatusCode() == 200){
                    log.info("Addon is installing...");
                }
                else {
                    log.error("Expected return code 200 or 303!");
                    log.error(response.getContent());
                    break;
                }

                Thread.sleep(5000);
            }

            if(!finished){
                log.error("Addon failed to install!");
                throw new MojoExecutionException("Addon failed to install");
            }

            // If no license provided in a text form but is provided by file...
            if((addonLicense == null || addonLicense.length() == 0) && addonLicenseFile != null){
                log.info("Reading license file: " + addonLicenseFile.getAbsolutePath());
                FileInputStream fileInputStream = new FileInputStream(addonLicenseFile);
                byte[] buffer = new byte[fileInputStream.available()];
                int length = fileInputStream.read(buffer);
                fileInputStream.close();
                addonLicense = new String(buffer, 0, length, Charsets.UTF_8);
            }

            // Upload addon license if one provided
            if(addonLicense != null && addonLicense.length() > 0) {
                // Check license
                response = Http.GET(baseUrl + "/rest/plugins/1.0/" + addonKey + "/license")
                        .withHeader("Accept", "*/*")
                        .withHeader("Content-Type", "application/vnd.atl.plugins+json")
                        .withHeader("Cookie", webSudoCookie)
                        .withBody("{\"rawLicense\":\"" + addonLicense + "\"}")
                        .send();
                log.info("GET \"" + baseUrl + "/rest/plugins/1.0/" + addonKey + "/license\" returned " + response.getStatusCode());

                jsonObject = (JSONObject) new JSONParser().parse(response.getContentToString());
                boolean hasLicense = jsonObject.containsKey("rawLicense");

                String licenseUpdateBody;
                if (hasLicense) {
                    jsonObject.put("rawLicense", addonLicense);
                    licenseUpdateBody = jsonObject.toJSONString();
                } else {
                    licenseUpdateBody = "{\"rawLicense\":\"" + addonLicense + "\"}";
                }

                // Upload addon license
                response = Http.PUT(baseUrl + "/rest/plugins/1.0/" + addonKey + "/license")
                        .withHeader("Accept", "*/*")
                        .withHeader("Content-Type", "application/vnd.atl.plugins+json")
                        .withHeader("Cookie", webSudoCookie)
                        .withBody(licenseUpdateBody)
                        .send();
                log.info("PUT \"" + baseUrl + "/rest/plugins/1.0/" + addonKey + "/license\" returned " + response.getStatusCode());

                if (response.getStatusCode() != 200) {
                    log.error("Expeced return code 200! License not updated!");
                    log.error(response.getContent());
                    throw new MojoExecutionException("License not updated!");
                } else {
                    log.info("License updated!");
                }
            }

        } catch (Exception e){
            e.printStackTrace();
            throw new MojoExecutionException("Something went wrong while uploading addon: " + e.getMessage());
        }
    }
}
