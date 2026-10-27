package io.fdrop.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Sends one HTTP request. An interface so tests can put faults between the client and a real server. */
interface Transport {

    HttpResponse<byte[]> send(HttpRequest request) throws IOException, InterruptedException;

    HttpResponse<InputStream> stream(HttpRequest request) throws IOException, InterruptedException;

    static Transport of(HttpClient http) {
        return new Transport() {
            @Override
            public HttpResponse<byte[]> send(HttpRequest request) throws IOException, InterruptedException {
                return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            }

            @Override
            public HttpResponse<InputStream> stream(HttpRequest request) throws IOException, InterruptedException {
                return http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            }
        };
    }
}
