package com.manning.apisecurityinaction.token;

import org.json.JSONObject;
import spark.Request;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.security.*;
import java.time.Instant;
import java.util.*;

import static java.nio.charset.StandardCharsets.UTF_8;

public class OAuth2TokenStore implements SecureTokenStore {

    private final URI introspectionEndpoint;
    private final URI revocationEndpoint;
    private final String authorization;

    private final HttpClient httpClient;

    public OAuth2TokenStore(URI introspectionEndpoint,
                            URI revocationEndpoint,
                            String clientId, String clientSecret) {
        this.introspectionEndpoint = introspectionEndpoint;
        this.revocationEndpoint = revocationEndpoint;

        var credentials = URLEncoder.encode(clientId, UTF_8) + ":" +
                URLEncoder.encode(clientSecret, UTF_8);
        this.authorization = "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(UTF_8));

        // Use "Intermediate" settings from https://wiki.mozilla.org/Security/Server_Side_TLS
        // As of 2019-06-30
        var sslParams = new SSLParameters();
        sslParams.setProtocols(new String[] { "TLSv1.3", "TLSv1.2" });
        sslParams.setCipherSuites(new String[] {
                // TLS 1.3 cipher suites
                "TLS_AES_128_GCM_SHA256",
                "TLS_AES_256_GCM_SHA384",
                "TLS_CHACHA20_POLY1305_SHA256",
                // TLS 1.2 cipher suites
                "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
                "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"
        });
        sslParams.setUseCipherSuitesOrder(true);
        sslParams.setEndpointIdentificationAlgorithm("HTTPS");

        try {
            var trustedCerts = KeyStore.getInstance("PKCS12");
            trustedCerts.load(
                    new FileInputStream("as.example.com.p12"),
                    "changeit".toCharArray());

            var tmf = TrustManagerFactory.getInstance("PKIX");
            tmf.init(trustedCerts);
            var sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(null, tmf.getTrustManagers(), null);

            this.httpClient = HttpClient.newBuilder()
                    .sslParameters(sslParams)
                    .sslContext(sslContext)
                    .build();

        } catch (GeneralSecurityException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String create(Request request, Token token) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Token> read(Request request, String tokenId) {
        if (!tokenId.matches("[\\x20-\\x7E]{1,1024}")) {
            return Optional.empty();
        }

        var form = "token=" + URLEncoder.encode(tokenId, UTF_8) +
                "&token_type_hint=access_token";

        var httpRequest = HttpRequest.newBuilder()
                .uri(introspectionEndpoint)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", authorization)
                .POST(BodyPublishers.ofString(form))
                .build();

        try {
            var httpResponse = httpClient.send(httpRequest,
                    BodyHandlers.ofString());

            if (httpResponse.statusCode() == 200) {
                var json = new JSONObject(httpResponse.body());

                if (json.getBoolean("active")) {
                    return processResponse(json);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }

        return Optional.empty();
    }

    @Override
    public void revoke(Request request, String tokenId) {
        if (!tokenId.matches("[\\x20-\\x7E]{1,1024}")) {
            throw new IllegalArgumentException("invalid token");
        }

        var form = "token=" + URLEncoder.encode(tokenId, UTF_8) +
                "&token_type_hint=access_token";

        var httpRequest = HttpRequest.newBuilder()
                .uri(revocationEndpoint)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", authorization)
                .POST(BodyPublishers.ofString(form))
                .build();

        try {
            httpClient.send(httpRequest, BodyHandlers.discarding());
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private Optional<Token> processResponse(JSONObject response) {
        var expiry = Instant.ofEpochSecond(response.optLong("exp"));
        var subject = response.optString("sub");
        if (subject == null) {
            return Optional.empty();
        }

        var token = new Token(expiry, subject);

        token.attributes.put("scope", response.getString("scope"));
        token.attributes.put("client_id", response.optString("client_id"));

        return Optional.of(token);
    }
}
