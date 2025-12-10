/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package io_jsonwebtoken.jjwt_gson;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.CompressionCodecs;
import io.jsonwebtoken.JwtParserBuilder;
import io.jsonwebtoken.IncorrectClaimException;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.io.Encoders;
import io.jsonwebtoken.security.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.MacAlgorithm;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.security.KeyPair;
import java.util.Collection;
import java.util.Date;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Jjwt_gsonTest {
    @Test
    void testSignedJWTs() {
        SecretKey firstKey = Jwts.SIG.HS256.key().build();
        String secretString = Encoders.BASE64.encode(firstKey.getEncoded());
        SecretKey secondKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secretString));

        assertThat(Jwts.parser().verifyWith(firstKey).build().parseSignedClaims(
                Jwts.builder().subject("Joe").signWith(firstKey, Jwts.SIG.HS256).compact()
        ).getPayload().getSubject()).isEqualTo("Joe");

        assertThat(Jwts.parser().verifyWith(secondKey).build().parseSignedClaims(
                Jwts.builder().subject("Joe").signWith(secondKey, Jwts.SIG.HS256).compact()
        ).getPayload().getSubject()).isEqualTo("Joe");
    }

    @Test
    void testCreatingAJWS() {
        Date firstDate = new Date();
        Date secondDate = new Date(System.currentTimeMillis() + 24 * 60 * 60 * 1000L);
        String uuidString = UUID.randomUUID().toString();
        SecretKey firstKey = Jwts.SIG.HS256.key().build();

        String firstCompactJws = Jwts.builder()
                .subject("Joe")
                .header().add("kid", "myKeyId").and()
                .issuer("Aaron")
                .audience().add("Abel").and()
                .expiration(secondDate)
                .notBefore(firstDate)
                .issuedAt(firstDate)
                .id(uuidString)
                .claim("exampleClaim", "Adam")
                .signWith(firstKey, Jwts.SIG.HS256)
                .compressWith(CompressionCodecs.GZIP)
                .compact();

        JwtParserBuilder jwtParserBuilder = Jwts.parser().clockSkewSeconds(3 * 60).verifyWith(firstKey);
        assertThat(jwtParserBuilder.build().parseSignedClaims(firstCompactJws).getPayload().getSubject()).isEqualTo("Joe");

        assertDoesNotThrow(() -> jwtParserBuilder.requireSubject("Joe").build().parseSignedClaims(firstCompactJws));
        assertDoesNotThrow(() -> jwtParserBuilder.requireIssuer("Aaron").build().parseSignedClaims(firstCompactJws));

        // Validate audience claim presence/value regardless of representation (String vs Collection)
        assertDoesNotThrow(() -> {
            Claims claims = jwtParserBuilder.build().parseSignedClaims(firstCompactJws).getPayload();
            Object audience = claims.get("aud");
            if (audience instanceof String) {
                assertThat(audience).isEqualTo("Abel");
            } else if (audience instanceof Collection) {
                assertThat(((Collection<?>) audience).stream().map(Object::toString).collect(Collectors.toList()))
                        .contains("Abel");
            } else {
                fail("Unexpected 'aud' claim type: " + (audience == null ? "null" : audience.getClass().getName()));
            }
        });

        // This is an error caused by GSON's own parsing
        assertThrows(IncorrectClaimException.class, () -> jwtParserBuilder.requireExpiration(secondDate).build().parseSignedClaims(firstCompactJws));
        assertThrows(IncorrectClaimException.class, () -> jwtParserBuilder.requireNotBefore(firstDate).build().parseSignedClaims(firstCompactJws));
        assertThrows(IncorrectClaimException.class, () -> jwtParserBuilder.requireIssuedAt(firstDate).build().parseSignedClaims(firstCompactJws));
        assertThrows(IncorrectClaimException.class, () -> jwtParserBuilder.requireId(uuidString).build().parseSignedClaims(firstCompactJws));
        assertThrows(IncorrectClaimException.class, () -> jwtParserBuilder.require("exampleClaim", "Adam").build().parseSignedClaims(firstCompactJws));
    }

    @Test
    void testCompression() {
        SecretKey firstKey = Jwts.SIG.HS256.key().build();
        assertThat(Jwts.parser().verifyWith(firstKey).build().parseSignedClaims(
                Jwts.builder().subject("Joe").signWith(firstKey, Jwts.SIG.HS256).compressWith(CompressionCodecs.DEFLATE).compact()
        ).getPayload().getSubject()).isEqualTo("Joe");
        assertThat(Jwts.parser().verifyWith(firstKey).build().parseSignedClaims(
                Jwts.builder().subject("Joe").signWith(firstKey, Jwts.SIG.HS256).compressWith(CompressionCodecs.GZIP).compact()
        ).getPayload().getSubject()).isEqualTo("Joe");
    }

    @Test
    void testSignatureAlgorithms() {
        // HMAC algorithms
        Stream.<MacAlgorithm>of(Jwts.SIG.HS256, Jwts.SIG.HS384, Jwts.SIG.HS512)
                .forEach(sigAlg -> {
                    SecretKey secretKey = sigAlg.key().build();
                    String jws = Jwts.builder().subject("Joe").signWith(secretKey, sigAlg).compact();
                    assertThat(Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(jws).getPayload().getSubject()).isEqualTo("Joe");
                });

        // EC, RSA, and RSA-PSS algorithms
        Stream.<SignatureAlgorithm>of(
                        Jwts.SIG.ES256, Jwts.SIG.ES384, Jwts.SIG.ES512,
                        Jwts.SIG.RS256, Jwts.SIG.RS384, Jwts.SIG.RS512,
                        Jwts.SIG.PS256, Jwts.SIG.PS384, Jwts.SIG.PS512)
                .forEach(sigAlg -> {
                    KeyPair keyPair = sigAlg.keyPair().build();
                    String jws = Jwts.builder().subject("Joe").signWith(keyPair.getPrivate(), sigAlg).compact();
                    assertThat(Jwts.parser().verifyWith(keyPair.getPublic()).build().parseSignedClaims(jws).getPayload().getSubject()).isEqualTo("Joe");
                });
    }
}
