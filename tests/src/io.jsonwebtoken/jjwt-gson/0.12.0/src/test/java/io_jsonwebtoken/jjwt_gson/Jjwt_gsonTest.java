/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package io_jsonwebtoken.jjwt_gson;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.jsonwebtoken.CompressionCodecs;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class Jjwt_gsonTest {

    @Test
    void testSignedJWTs() {
        // Create an unsecured JWT and verify claims parsing using the Gson serializer/deserializer
        String jwt = Jwts.builder().setSubject("Joe").compact();

        JsonObject header = parseHeader(jwt);
        assertThat(header.get("alg").getAsString()).isEqualTo("none");

        String payloadJson = assertDoesNotThrow(() -> decodePayloadJson(jwt, header));
        JsonObject claims = JsonParser.parseString(payloadJson).getAsJsonObject();
        assertThat(claims.get("sub").getAsString()).isEqualTo("Joe");
    }

    @Test
    void testCreatingAJWS() {
        Date firstDate = new Date();
        Date secondDate = new Date(System.currentTimeMillis() + 24 * 60 * 60 * 1000L);
        String uuidString = UUID.randomUUID().toString();

        // Build a compressed unsecured JWT to exercise Gson claim handling
        String compactJwt = Jwts.builder()
                .setSubject("Joe")
                .setHeaderParam("kid", "myKeyId")
                .setIssuer("Aaron")
                .setAudience("Abel")
                .setExpiration(secondDate)
                .setNotBefore(firstDate)
                .setIssuedAt(firstDate)
                .setId(uuidString)
                .claim("exampleClaim", "Adam")
                .compressWith(CompressionCodecs.GZIP)
                .compact();

        JsonObject header = parseHeader(compactJwt);
        assertThat(header.get("alg").getAsString()).isEqualTo("none");
        assertThat(header.get("kid").getAsString()).isEqualTo("myKeyId");
        assertThat(header.get("zip").getAsString()).isEqualTo("GZIP");

        String payloadJson = assertDoesNotThrow(() -> decodePayloadJson(compactJwt, header));
        JsonObject claims = JsonParser.parseString(payloadJson).getAsJsonObject();

        assertThat(claims.get("sub").getAsString()).isEqualTo("Joe");
        assertThat(claims.get("iss").getAsString()).isEqualTo("Aaron");
        assertThat(claims.get("aud").getAsString()).isEqualTo("Abel");
        assertThat(claims.get("jti").getAsString()).isEqualTo(uuidString);
        assertThat(claims.get("exampleClaim").getAsString()).isEqualTo("Adam");

        // Temporal claims should be numeric (seconds or milliseconds). Validate with tolerance:
        assertTemporalClaimApprox(claims, "exp", secondDate);
        assertTemporalClaimApprox(claims, "nbf", firstDate);
        assertTemporalClaimApprox(claims, "iat", firstDate);
    }

    @Test
    void testCompression() {
        // Verify both supported compression codecs on unsecured JWTs
        String deflated = Jwts.builder().setSubject("Joe").compressWith(CompressionCodecs.DEFLATE).compact();
        {
            JsonObject header = parseHeader(deflated);
            assertThat(header.get("alg").getAsString()).isEqualTo("none");
            assertThat(header.get("zip").getAsString()).isEqualTo("DEF");
            String payloadJson = assertDoesNotThrow(() -> decodePayloadJson(deflated, header));
            JsonObject claims = JsonParser.parseString(payloadJson).getAsJsonObject();
            assertThat(claims.get("sub").getAsString()).isEqualTo("Joe");
        }

        String gzipped = Jwts.builder().setSubject("Joe").compressWith(CompressionCodecs.GZIP).compact();
        {
            JsonObject header = parseHeader(gzipped);
            assertThat(header.get("alg").getAsString()).isEqualTo("none");
            assertThat(header.get("zip").getAsString()).isEqualTo("GZIP");
            String payloadJson = assertDoesNotThrow(() -> decodePayloadJson(gzipped, header));
            JsonObject claims = JsonParser.parseString(payloadJson).getAsJsonObject();
            assertThat(claims.get("sub").getAsString()).isEqualTo("Joe");
        }
    }

    @Test
    void testSignatureAlgorithms() {
        // Ensure the builder accepts various algorithms without performing a signature (no compact()),
        // using explicit algorithm selection to avoid reflective key inspection.
        Stream.of(SignatureAlgorithm.HS256, SignatureAlgorithm.HS384, SignatureAlgorithm.HS512)
                .forEach(algo -> {
                    var secretKey = Keys.secretKeyFor(algo);
                    assertDoesNotThrow(() ->
                            Jwts.builder().setSubject("Joe").signWith(secretKey, algo)
                    );
                });

        Stream.of(SignatureAlgorithm.ES256, SignatureAlgorithm.ES384, SignatureAlgorithm.ES512,
                        SignatureAlgorithm.RS256, SignatureAlgorithm.RS384, SignatureAlgorithm.RS512,
                        SignatureAlgorithm.PS256, SignatureAlgorithm.PS384, SignatureAlgorithm.PS512)
                .forEach(algo -> {
                    var keyPair = Keys.keyPairFor(algo);
                    assertDoesNotThrow(() ->
                            Jwts.builder().setSubject("Joe").signWith(keyPair.getPrivate(), algo)
                    );
                });
    }

    // Helpers

    private static JsonObject parseHeader(String compact) {
        String[] parts = compact.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid compact JWT: " + compact);
        }
        String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        return JsonParser.parseString(headerJson).getAsJsonObject();
    }

    private static String decodePayloadJson(String compact, JsonObject header) throws Exception {
        String[] parts = compact.split("\\.");
        byte[] payload = Base64.getUrlDecoder().decode(parts[1]);

        if (header.has("zip")) {
            String zip = header.get("zip").getAsString();
            InputStream in = new ByteArrayInputStream(payload);
            if ("GZIP".equalsIgnoreCase(zip)) {
                in = new GZIPInputStream(in);
            } else if ("DEF".equalsIgnoreCase(zip) || "DEFLATE".equalsIgnoreCase(zip)) {
                in = new InflaterInputStream(in);
            }
            payload = readAllBytes(in);
        }

        return new String(payload, StandardCharsets.UTF_8);
    }

    private static byte[] readAllBytes(InputStream in) throws Exception {
        try (in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
            return out.toByteArray();
        }
    }

    private static void assertTemporalClaimApprox(JsonObject claims, String name, Date expected) {
        assertThat(claims.has(name)).as("claim '%s' present", name).isTrue();
        long value = claims.get(name).getAsLong();

        // Accept seconds or milliseconds with small tolerances:
        long expectedSeconds = expected.getTime() / 1000L;
        boolean matchesSeconds = Math.abs(value - expectedSeconds) <= 1;

        long expectedMillis = expected.getTime();
        boolean matchesMillis = Math.abs(value - expectedMillis) <= 1000;

        assertThat(matchesSeconds || matchesMillis)
                .as("temporal claim '%s' approx equals expected time", name)
                .isTrue();
    }
}
