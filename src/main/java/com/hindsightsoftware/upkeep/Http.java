package com.hindsightsoftware.upkeep;

import com.sun.java.swing.plaf.windows.WindowsTreeUI;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HeaderElement;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.*;
import org.apache.http.client.params.ClientPNames;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;
import org.apache.http.util.EntityUtils;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class Http {
    public enum Method {
        POST,
        GET,
        DELETE,
        PUT,
    }

    public static class Response {
        private final HttpResponse response;
        private final HttpEntity entity;

        public Response(HttpResponse response){
            this.response = response;
            this.entity = response.getEntity();
        }

        public long getContentLength() {
            return entity.getContentLength();
        }

        public String getContentToString() throws IOException {
            return EntityUtils.toString(entity);
        }

        public File getFile() throws IOException {
            Header header = response.getLastHeader("Content-Disposition");
            String filename = "app.obr";
            if (header != null) {
                HeaderElement[] elements = header.getElements();
                for (HeaderElement element : elements) {
                    if (element.getName().equalsIgnoreCase("attachment")) {
                        NameValuePair nmv = element.getParameterByName("filename");
                        if (nmv != null) {
                            filename = nmv.getValue();
                        }
                    }
                }
            }

            File file = new File(filename);
            FileOutputStream fos = new FileOutputStream(file);
            entity.writeTo(fos);
            return file;
        }

        public String getContent() throws IOException {
            InputStream inputStream = entity.getContent();

            final StringBuilder out = new StringBuilder();
            Reader in = new InputStreamReader(inputStream, "UTF-8");
            char buffer[] = new char[1024];
            try {
                while(inputStream.available() > 0){
                    int len = in.read(buffer, 0, 1024);
                    if(len <= 0)break;
                    out.append(buffer, 0, len);
                }
                return out.toString();
            } finally {
                inputStream.close();
            }
        }

        public List<String> getHeader(String key) {
            Header[] headers = response.getHeaders(key);
            if(headers.length > 0)return Arrays.stream(headers).map(Header::getValue).collect(Collectors.toList());
            return new ArrayList<String>();
        }

        public int getStatusCode() {
            return response.getStatusLine().getStatusCode();
        }
    }

    public static class Request {
        private Method method;
        private HttpRequestBase httpRequest;
        private HttpClient httpClient;

        public Request(Method method, String uri) throws IllegalArgumentException {
            this.method = method;
            this.httpClient = HttpClients.createDefault();
            switch(method){
                case POST:
                    this.httpRequest = new HttpPost(uri);
                    break;
                case GET:
                    this.httpRequest = new HttpGet(uri);
                    break;
                case DELETE:
                    this.httpRequest = new HttpDelete(uri);
                    break;
                case PUT:
                    this.httpRequest = new HttpPut(uri);
                    break;
                default:
                    throw new IllegalArgumentException("Invalid method");
            }
        }

        public Request withHeader(String name, String value){
            httpRequest.addHeader(name, value);
            return this;
        }

        public Request withForm(List<NameValuePair> values) throws UnsupportedEncodingException {
            if(method == Method.POST){
                ((HttpPost)httpRequest).setEntity(new UrlEncodedFormEntity(values, "UTF-8"));
            }
            return this;
        }

        public Request withBody(String body) throws UnsupportedEncodingException {
            if(method == Method.POST){
                ((HttpPost)httpRequest).setEntity(new ByteArrayEntity(body.getBytes("UTF-8")));
            }
            else if(method == Method.PUT){
                ((HttpPut)httpRequest).setEntity(new ByteArrayEntity(body.getBytes("UTF-8")));
            }
            return this;
        }

        public Request withFollowRedirects(boolean follow){
            HttpParams params = new BasicHttpParams();
            params.setParameter(ClientPNames.HANDLE_REDIRECTS, follow);
            httpRequest.setParams(params);
            return this;
        }

        public Request withFile(String key, File file){
            if(method == Method.POST) {
                MultipartEntityBuilder builder = MultipartEntityBuilder.create();
                builder.setMode(HttpMultipartMode.BROWSER_COMPATIBLE);
                FileBody fileBody = new FileBody(file, ContentType.DEFAULT_BINARY);
                builder.addPart(key, fileBody);
                HttpEntity entity = builder.build();
                ((HttpPost)httpRequest).setEntity(entity);
            }
            return this;
        }

        public Request timeout(int seconds){
            RequestConfig requestConfig = RequestConfig.custom()
                    .setConnectionRequestTimeout(seconds * 1000)
                    .setConnectTimeout(seconds * 1000)
                    .setSocketTimeout(seconds * 1000).build();
            httpRequest.setConfig(requestConfig);
            return this;
        }

        public Response send() throws IOException {
            return new Response(httpClient.execute(httpRequest));
        }
    }

    public static Request POST(String uri){
        return new Request(Method.POST, uri);
    }

    public static Request GET(String uri){
        return new Request(Method.GET, uri);
    }

    public static Request DELETE(String uri){
        return new Request(Method.DELETE, uri);
    }

    public static Request PUT(String uri){
        return new Request(Method.PUT, uri);
    }
}
