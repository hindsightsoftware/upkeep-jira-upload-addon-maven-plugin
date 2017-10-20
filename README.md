Upkeep: jira-upload-addon-maven-plugin
======================================
Maven plugin for uploading addon into JIRA Server and updating addon license. See 
<https://github.com/hindsightsoftware/upkeep-jira-example> project for more detailed use case example.

### Parameters

* **jira.addon.upload.url** - URL to JIRA
* **jira.addon.upload.url.file** - File that contains JIRA URL. If this parameter is specified, 
  the *jira.addon.upload.url* is ignored and the URL from file is used.
* **jira.addon.file** - Path to addon file (the OBR file).
* **jira.addon.key** - Addon key that needs to match addon file.
* **jira.addon.login.username** (default: admin) - Username that will be used to log in and upload the addon. The account associated with this
  username has to have websudo access (an access to JIRA system and addons).
* **jira.addon.login.password** (default: admin) - Password for the username.
* **jira.max.wait** (default: 300) - Maximum wait time while waiting for addon to be installed in seconds.
* **jira.addon.license.enabled** (default: true) - Set to false if you wish no addon license to be updated.
* **jira.addon.license** - License (in text form) for the addon. You can use the timebomb license for testing which is 
  valid for 3 hours: 
  <https://developer.atlassian.com/platform/marketplace/timebomb-licenses-for-testing-server-apps/>
* **jira.addon.license.file** - File that contains addon license. If a file is specified, the *jira.addon.license*
  parameter is ignored and the license from this file is used. 

### Sample maven configuration
```xml
<project>
    <!-- Common project settings -->
    <properties>
      <jira.addon.upload.url>http://localhost:8080/jira</jira.addon.upload.url>
      <jira.addon.file>my-special-addon.obr</jira.addon.file>
      <jira.addon.key>com.example.addon-key</jira.addon.key>
      <jira.addon.login.username>admin</jira.login.username>
      <jira.addon.login.password>admin</jira.login.password>
      <jira.addon.license.enabled>true</jira.addon.license.enabled>
      <jira.addon.license>addon license here, or use jira.addon.license.file</jira.addon.license>
      
      <skipAddonUpload>false</skipAddonUpload>
    </properties>
    <!-- dependencies, etc... -->
    <build>
        <plugins>
            <plugin>
                <groupId>com.hindsightsoftware.upkeep</groupId>
                <artifactId>jira-upload-addon-maven-plugin</artifactId>
                <version>1.0</version>
                <executions>
                    <execution>
                        <id>upload-jira-addon</id>
                        <phase>pre-integration-test</phase>
                        <goals>
                            <goal>upload</goal>
                        </goals>
                        <configuration>
                            <skip>${skipAddonUpload}</skip>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
            <!-- More plugins here -->
        </plugins>
    </build>
</project>
```

