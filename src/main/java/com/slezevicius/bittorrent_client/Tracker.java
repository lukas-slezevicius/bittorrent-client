package com.slezevicius.bittorrent_client;

import java.io.IOException;
import java.io.InputStream;

import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

class Tracker {

    public static byte[] sendRequest(String url) {
        CloseableHttpClient httpclient = HttpClients.createDefault();
        HttpGet httpGet = new HttpGet(url);
        try {
            CloseableHttpResponse response = httpclient.execute(httpGet);
            System.out.println(response.getStatusLine());
            HttpEntity entity = response.getEntity();
            InputStream stream = entity.getContent();
            byte[] content = stream.readAllBytes();
            stream.close();
            EntityUtils.consume(entity);
            System.out.println(content.length);
            return content;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
}