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
    private static final String LICENSE_3H_EXPIRATION = 
        "AAABCA0ODAoPeNpdj01PwkAURffzKyZxZ1IyUzARkllQ24gRaQMtGnaP8VEmtjPNfFT59yJVFyzfu\n" +
        "bkn796Ux0Bz6SmbUM5nbDzj97RISxozHpMUnbSq88poUaLztFEStUN6MJZ2TaiVpu/YY2M6tI6sQ\n" +
        "rtHmx8qd74EZ+TBIvyUU/AoYs7jiE0jzknWQxMuifA2IBlUbnQ7AulVjwN9AaU9atASs69O2dNFU\n" +
        "4wXJLc1aOUGw9w34JwCTTZoe7RPqUgep2X0Vm0n0fNut4gSxl/Jcnj9nFb6Q5tP/Ueu3L+0PHW4g\n" +
        "hZFmm2zZV5k6/95CbR7Y9bYGo/zGrV3Ir4jRbDyCA6vt34DO8p3SDAsAhQnJjLD5k9Fr3uaIzkXK\n" +
        "f83o5vDdQIUe4XequNCC3D+9ht9ZYhNZFKmnhc=X02dh";

    @Parameter( property = "jira-url", defaultValue = "http://localhost:8080" )
    private String baseUrl;

    @Parameter( property = "jira-url-file", defaultValue = "" )
    private File baseUrlFile;

    @Parameter( property = "addon-file", defaultValue = "" )
    private File addonFile;

    @Parameter(property = "addon-url", defaultValue = "")
    private String addonUrl;

    @Parameter( property = "addon-key", defaultValue = "" )
    private String addonKey;

    @Parameter( property = "username", defaultValue = "admin" )
    private String username;

    @Parameter( property = "password", defaultValue = "admin" )
    private String password;

    @Parameter( property = "wait", defaultValue = "300" )
    private int maxWaitTime;

    @Parameter( property = "license-skip", defaultValue = "true" )
    private boolean addonLicenseSkip;

    @Parameter( property = "license", defaultValue = LICENSE_3H_EXPIRATION )
    private String addonLicense;

    @Parameter( property = "license-file", defaultValue = "" )
    private File addonLicenseFile;

    @Parameter
    private boolean skip;

    private Log log;

    public void setLog(Log log){
        this.log = new SystemStreamLog();
    }

    public void execute() throws MojoExecutionException {
        if(skip)return;

        try {
            // If no license provided in a text form but is provided by file...
            if(baseUrlFile != null){
                log.info("Reading base URL file: " + baseUrlFile.getAbsolutePath());
                baseUrl = fileToString(baseUrlFile);
            }

            if(baseUrl.indexOf("http://") != 0 && baseUrl.indexOf("https://") != 0){
                baseUrl = "http://" + baseUrl;
            }
            
            if(addonUrl != null) {
                log.info("Downloading addon from: " + addonUrl);
                Http.Response response = Http.GET(addonUrl).timeout(30).send();
                addonFile = response.getFile();
            }

            if (addonFile == null) {
                throw new MojoExecutionException("Please provide addonFile or addonUrl");
            }

            log.info("JIRA base url: " + baseUrl);
            log.info("JIRA addon key: " + addonKey);
            log.info("JIRA addon file: " + addonFile.getAbsolutePath());

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
            String cookie = sessionName + "=" + sessionValue;
            for(String header : response.getHeader("Set-Cookie")){
                cookie += "; " + header;
            }

            log.info("Got session name: " + sessionName);
            log.info("Got session value: " + sessionValue);

            // Web sudo authenticate
            response = Http.POST(baseUrl + "/secure/admin/WebSudoAuthenticate.jspa")
                    .withHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8")
                    .withHeader("Content-Type", "application/x-www-form-urlencoded")
                    .withHeader("Cookie", cookie)
                    .withForm(Arrays.asList(
                            new BasicNameValuePair("webSudoPassword", password),
                            new BasicNameValuePair("webSudoDestination", "/plugins/servlet/upm/marketplace"),
                            new BasicNameValuePair("webSudoIsPost", "false")
                    ))
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
            else if(response.getStatusCode() == 500){
                throw new MojoExecutionException("Http error 500! Did you forget to add \"-key\" suffix into addon key?");
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

            // Should we upload license?
            if(!addonLicenseSkip){
                log.info("Skipping updating addon license...");
                return;
            }

            // If no license provided in a text form but is provided by file...
            if(addonLicenseFile != null){
                log.info("Reading license file: " + addonLicenseFile.getAbsolutePath());
                addonLicense = fileToString(addonLicenseFile);
            }

            // Fix new line characters
            addonLicense = addonLicense.replace("\n", "\\n");

            // Upload addon license phase..

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

            if (hasLicense) {
                jsonObject.put("rawLicense", "");
                String licenseUpdateBody = jsonObject.toJSONString();

                // Remove addon license
                response = Http.DELETE(baseUrl + "/rest/plugins/1.0/" + addonKey + "/license")
                    .withHeader("Accept", "*/*")
                    .withHeader("Content-Type", "application/vnd.atl.plugins+json")
                    .withHeader("Cookie", webSudoCookie)
                    .withBody(licenseUpdateBody)
                    .send();

                if (response.getStatusCode() != 200) {
                    log.error("Expeced return code 200! License not removed!");
                    log.error(response.getContentToString());
                    throw new MojoExecutionException("License not removed!");
                }
            }

            // Upload addon license
            response = Http.PUT(baseUrl + "/rest/plugins/1.0/" + addonKey + "/license")
                    .withHeader("Accept", "*/*")
                    .withHeader("Content-Type", "application/vnd.atl.plugins+json")
                    .withHeader("Cookie", webSudoCookie)
                    .withBody("{\"rawLicense\":\"" + addonLicense + "\"}")
                    .send();
            log.info("PUT \"" + baseUrl + "/rest/plugins/1.0/" + addonKey + "/license\" returned " + response.getStatusCode());

            if (response.getStatusCode() != 200) {
                log.error("Expeced return code 200! License not updated!");
                log.error(response.getContentToString());
                throw new MojoExecutionException("License not updated!");
            } else {
                log.info("License updated!");
            }

        } catch (Exception e){
            e.printStackTrace();
            throw new MojoExecutionException("Something went wrong while uploading addon: " + e.getMessage());
        }
    }

    private static String fileToString(File file) throws IOException {
        FileInputStream fileInputStream = new FileInputStream(file);
        byte[] buffer = new byte[fileInputStream.available()];
        int length = fileInputStream.read(buffer);
        fileInputStream.close();
        return new String(buffer, 0, length, Charsets.UTF_8).replaceAll("\n","");
    }
}
