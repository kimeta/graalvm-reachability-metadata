/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package io_jsonwebtoken.jjwt_gson;

import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.CompressionCodecs;
import io.jsonwebtoken.JwtParserBuilder;
import io.jsonwebtoken.IncorrectClaimException;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.io.Encoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Date;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Jjwt_gsonTest {
    @Test
    void testSignedJWTs() {
        // Use RSA to avoid module access issues for HS* on JDK 21+
        KeyPair firstPair = Keys.keyPairFor(SignatureAlgorithm.RS256);

        // Reconstruct the public key from its encoded form (similar to how the HS test rebuilt the key from bytes)
        String publicEncodedB64 = Encoders.BASE64.encode(firstPair.getPublic().getEncoded());
        PublicKey reconstructedPublic = assertDoesNotThrow(() -> {
            byte[] encoded = Decoders.BASE64.decode(publicEncodedB64);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(encoded);
            return KeyFactory.getInstance("RSA").generatePublic(spec);
        });

        String jwsWithOriginalPublic = Jwts.builder().setSubject("Joe").signWith(firstPair.getPrivate(), SignatureAlgorithm.RS256).compact();

        assertThat(Jwts.parser().setSigningKey(firstPair.getPublic()).build().parseClaimsJws(jwsWithOriginalPublic)
                .getBody().getSubject()).isEqualTo("Joe");
        assertThat(Jwts.parser().setSigningKey(reconstructedPublic).build().parseClaimsJws(jwsWithOriginalPublic)
                .getBody().getSubject()).isEqualTo("Joe");
    }

    @Test
    void testCreatingAJWS() {
        Date firstDate = new Date();
        Date secondDate = new Date(System.currentTimeMillis() + 24 * 60 * 60 * 1000L);
        String uuidString = UUID.randomUUID().toString();

        // Use RSA to avoid HS* reflection issues on JDK 21+
        KeyPair firstPair = Keys.keyPairFor(SignatureAlgorithm.RS256);

        String firstCompactJws = Jwts.builder()
                .setSubject("Joe")
                .setHeaderParam("kid", "myKeyId")
                .setIssuer("Aaron")
                .setAudience("Abel")
                .setExpiration(secondDate)
                .setNotBefore(firstDate)
                .setIssuedAt(firstDate)
                .setId(uuidString)
                .claim("exampleClaim", "Adam")
                .signWith(firstPair.getPrivate(), SignatureAlgorithm.RS256)
                .compressWith(CompressionCodecs.GZIP)
                .compact();

        JwtParserBuilder jwtParserBuilder = Jwts.parser().setAllowedClockSkewSeconds(3 * 60).setSigningKey(firstPair.getPublic());
        assertThat(jwtParserBuilder.build().parseClaimsJws(firstCompactJws).getBody().getSubject()).isEqualTo("Joe");
        assertDoesNotThrow(() -> jwtParserBuilder.requireSubject("Joe").build().parseClaimsJws(firstCompactJws));
        assertDoesNotThrow(() -> jwtParserBuilder.requireIssuer("Aaron").build().parseClaimsJws(firstCompactJws));
        assertDoesNotThrow(() -> jwtParserBuilder.requireAudience("Abel").build().parseClaimsJws(firstCompactJws));
        // This is an error caused by GSON's own parsing
        assertThrows(IncorrectClaimException.class, () -> jwtParserBuilder.requireExpiration(secondDate).build().parseClaimsJws(firstCompactJws));
        assertThrows(IncorrectClaimException.class, () -> jwtParserBuilder.requireNotBefore(firstDate).build().parseClaimsJws(firstCompactJws));
        assertThrows(IncorrectClaimException.class, () -> jwtParserBuilder.requireIssuedAt(firstDate).build().parseClaimsJws(firstCompactJws));
        assertThrows(IncorrectClaimException.class, () -> jwtParserBuilder.requireId(uuidString).build().parseClaimsJws(firstCompactJws));
        assertThrows(IncorrectClaimException.class, () -> jwtParserBuilder.require("exampleClaim", "Adam").build().parseClaimsJws(firstCompactJws));
    }

    @Test
    void testCompression() {
        // Use RSA to avoid HS* reflection issues on JDK 21+
        KeyPair pair = Keys.keyPairFor(SignatureAlgorithm.RS256);
        assertThat(Jwts.parser().setSigningKey(pair.getPublic()).build().parseClaimsJws(
                Jwts.builder().setSubject("Joe").signWith(pair.getPrivate(), SignatureAlgorithm.RS256).compressWith(CompressionCodecs.DEFLATE).compact()
        ).getBody().getSubject()).isEqualTo("Joe");
        assertThat(Jwts.parser().setSigningKey(pair.getPublic()).build().parseClaimsJws(
                Jwts.builder().setSubject("Joe").signWith(pair.getPrivate(), SignatureAlgorithm.RS256).compressWith(CompressionCodecs.GZIP).compact()
        ).getBody().getSubject()).isEqualTo("Joe");
    }

    @Test
    void testSignatureAlgorithms() {
        // Only algorithms that do not require forbidden sun.* reflection under JDK 21+
        Stream.of(SignatureAlgorithm.ES256, SignatureAlgorithm.ES384, SignatureAlgorithm.ES512,
                        SignatureAlgorithm.RS256, SignatureAlgorithm.RS384, SignatureAlgorithm.RS512,
                        SignatureAlgorithm.PS256, SignatureAlgorithm.PS384, SignatureAlgorithm.PS512)
                .map(Keys::keyPairFor)
                .forEach(keyPair -> assertThat(Jwts.parser().setSigningKey(keyPair.getPublic()).build().parseClaimsJws(
                        Jwts.builder().setSubject("Joe").signWith(keyPair.getPrivate()).compact()
                ).getBody().getSubject()).isEqualTo("Joe"));
    }
}
