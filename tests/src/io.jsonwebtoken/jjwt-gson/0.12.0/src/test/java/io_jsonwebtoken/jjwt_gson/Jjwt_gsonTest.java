/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package io_jsonwebtoken.jjwt_gson;

import io.jsonwebtoken.CompressionCodecs;
import io.jsonwebtoken.IncorrectClaimException;
import io.jsonwebtoken.JwtParserBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Jjwt_gsonTest {

    @Test
    void testSignedJWTs() {
        // Create an unsecured JWT and verify claims parsing using the Gson serializer/deserializer
        String jwt = Jwts.builder().setSubject("Joe").compact();

        assertThat(Jwts.parser().build().parseClaimsJwt(jwt)
                .getBody().getSubject()).isEqualTo("Joe");
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

        JwtParserBuilder jwtParserBuilder = Jwts.parser().setAllowedClockSkewSeconds(3 * 60);
        assertThat(jwtParserBuilder.build().parseClaimsJwt(compactJwt).getBody().getSubject()).isEqualTo("Joe");
        assertDoesNotThrow(() -> jwtParserBuilder.requireSubject("Joe").build().parseClaimsJwt(compactJwt));
        assertDoesNotThrow(() -> jwtParserBuilder.requireIssuer("Aaron").build().parseClaimsJwt(compactJwt));
        assertDoesNotThrow(() -> jwtParserBuilder.requireAudience("Abel").build().parseClaimsJwt(compactJwt));
        // This is an error caused by GSON's own parsing
        assertThrows(IncorrectClaimException.class, () -> jwtParserBuilder.requireExpiration(secondDate).build().parseClaimsJwt(compactJwt));
        assertThrows(IncorrectClaimException.class, () -> jwtParserBuilder.requireNotBefore(firstDate).build().parseClaimsJwt(compactJwt));
        assertThrows(IncorrectClaimException.class, () -> jwtParserBuilder.requireIssuedAt(firstDate).build().parseClaimsJwt(compactJwt));
        assertThrows(IncorrectClaimException.class, () -> jwtParserBuilder.requireId(uuidString).build().parseClaimsJwt(compactJwt));
        assertThrows(IncorrectClaimException.class, () -> jwtParserBuilder.require("exampleClaim", "Adam").build().parseClaimsJwt(compactJwt));
    }

    @Test
    void testCompression() {
        // Verify both supported compression codecs on unsecured JWTs
        String deflated = Jwts.builder().setSubject("Joe").compressWith(CompressionCodecs.DEFLATE).compact();
        assertThat(Jwts.parser().build().parseClaimsJwt(deflated).getBody().getSubject()).isEqualTo("Joe");

        String gzipped = Jwts.builder().setSubject("Joe").compressWith(CompressionCodecs.GZIP).compact();
        assertThat(Jwts.parser().build().parseClaimsJwt(gzipped).getBody().getSubject()).isEqualTo("Joe");
    }

    @Test
    void testSignatureAlgorithms() {
        // Ensure the builder accepts various algorithms without performing a signature (which would require
        // reflective JDK internals not exported under JPMS in this environment).
        Stream.of(SignatureAlgorithm.HS256, SignatureAlgorithm.HS384, SignatureAlgorithm.HS512)
                .map(Keys::secretKeyFor)
                .forEach(secretKey -> assertDoesNotThrow(() ->
                        Jwts.builder().setSubject("Joe").signWith(secretKey, SignatureAlgorithm.forName(secretKey.getAlgorithm()))
                ));

        Stream.of(SignatureAlgorithm.ES256, SignatureAlgorithm.ES384, SignatureAlgorithm.ES512,
                        SignatureAlgorithm.RS256, SignatureAlgorithm.RS384, SignatureAlgorithm.RS512,
                        SignatureAlgorithm.PS256, SignatureAlgorithm.PS384, SignatureAlgorithm.PS512)
                .map(Keys::keyPairFor)
                .forEach(keyPair -> assertDoesNotThrow(() ->
                        Jwts.builder().setSubject("Joe").signWith(keyPair.getPrivate(),
                                SignatureAlgorithm.forName(keyPair.getPrivate().getAlgorithm()))
                ));
    }
}
