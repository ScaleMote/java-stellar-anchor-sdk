package org.stellar.anchor.auth;

import static java.util.Date.*;

import io.jsonwebtoken.*;
import io.jsonwebtoken.impl.DefaultJwsHeader;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import lombok.Getter;
import org.apache.commons.codec.binary.Base64;
import org.stellar.anchor.api.exception.InvalidConfigException;
import org.stellar.anchor.api.exception.NotSupportedException;
import org.stellar.anchor.auth.ApiAuthJwt.CallbackAuthJwt;
import org.stellar.anchor.auth.ApiAuthJwt.CustodyAuthJwt;
import org.stellar.anchor.auth.ApiAuthJwt.PlatformAuthJwt;
import org.stellar.anchor.config.CustodySecretConfig;
import org.stellar.anchor.config.SecretConfig;

@Getter
public class JwtService {
  // SEP-24 specific claims
  public static final String CLIENT_DOMAIN = "client_domain";
  public static final String HOME_DOMAIN = "home_domain";
  public static final String CLIENT_NAME = "client_name";

  String sep10JwtSecret;
  String sep24InteractiveUrlJwtSecret;
  String sep24MoreInfoUrlJwtSecret;
  String callbackAuthSecret;
  String platformAuthSecret;
  String custodyAuthSecret;

  public JwtService(SecretConfig secretConfig, CustodySecretConfig custodySecretConfig)
      throws NotSupportedException {
    this(
        secretConfig.getSep10JwtSecretKey(),
        secretConfig.getSep24InteractiveUrlJwtSecret(),
        secretConfig.getSep24MoreInfoUrlJwtSecret(),
        secretConfig.getCallbackAuthSecret(),
        secretConfig.getPlatformAuthSecret(),
        custodySecretConfig.getCustodyAuthSecret());
  }

  public JwtService(
      String sep10JwtSecret,
      String sep24InteractiveUrlJwtSecret,
      String sep24MoreInfoUrlJwtSecret,
      String callbackAuthSecret,
      String platformAuthSecret,
      String custodyAuthSecret) {
    this.sep10JwtSecret = toBase64OrNull(sep10JwtSecret);
    this.sep24InteractiveUrlJwtSecret = toBase64OrNull(sep24InteractiveUrlJwtSecret);
    this.sep24MoreInfoUrlJwtSecret = toBase64OrNull(sep24MoreInfoUrlJwtSecret);
    this.callbackAuthSecret = toBase64OrNull(callbackAuthSecret);
    this.platformAuthSecret = toBase64OrNull(platformAuthSecret);
    this.custodyAuthSecret = toBase64OrNull(custodyAuthSecret);
  }

  public String encode(Sep10Jwt token) {
    Instant timeExp = Instant.ofEpochSecond(token.getExp());
    Instant timeIat = Instant.ofEpochSecond(token.getIat());
    JwtBuilder builder =
        Jwts.builder()
            .setId(token.getJti())
            .setIssuer(token.getIss())
            .setSubject(token.getSub())
            .setIssuedAt(from(timeIat))
            .setExpiration(from(timeExp))
            .setSubject(token.getSub());

    if (token.getClientDomain() != null) {
      builder.claim(CLIENT_DOMAIN, token.getClientDomain());
    }

    if (token.getHomeDomain() != null) {
      builder.claim(HOME_DOMAIN, token.getHomeDomain());
    }

    return builder.signWith(SignatureAlgorithm.HS256, sep10JwtSecret).compact();
  }

  public String encode(Sep24InteractiveUrlJwt token) throws InvalidConfigException {
    if (sep24InteractiveUrlJwtSecret == null) {
      throw new InvalidConfigException(
          "Please provide the secret before encoding JWT for Sep24 interactive url");
    }
    Instant timeExp = Instant.ofEpochSecond(token.getExp());
    JwtBuilder builder =
        Jwts.builder()
            .setId(token.getJti())
            .setExpiration(from(timeExp))
            .setSubject(token.getSub());
    for (Map.Entry<String, Object> claim : token.claims.entrySet()) {
      builder.claim(claim.getKey(), claim.getValue());
    }

    return builder.signWith(SignatureAlgorithm.HS256, sep24InteractiveUrlJwtSecret).compact();
  }

  public String encode(Sep24MoreInfoUrlJwt token) throws InvalidConfigException {
    if (sep24MoreInfoUrlJwtSecret == null) {
      throw new InvalidConfigException(
          "Please provide the secret before encoding JWT for more_info_url");
    }
    Instant timeExp = Instant.ofEpochSecond(token.getExp());
    JwtBuilder builder =
        Jwts.builder()
            .setId(token.getJti())
            .setExpiration(from(timeExp))
            .setSubject(token.getSub());
    for (Map.Entry<String, Object> claim : token.claims.entrySet()) {
      builder.claim(claim.getKey(), claim.getValue());
    }

    return builder.signWith(SignatureAlgorithm.HS256, sep24MoreInfoUrlJwtSecret).compact();
  }

  public String encode(CallbackAuthJwt token) throws InvalidConfigException {
    return encode(token, callbackAuthSecret);
  }

  public String encode(PlatformAuthJwt token) throws InvalidConfigException {
    return encode(token, platformAuthSecret);
  }

  public String encode(CustodyAuthJwt token) throws InvalidConfigException {
    return encode(token, custodyAuthSecret);
  }

  private String encode(ApiAuthJwt token, String secret) throws InvalidConfigException {
    if (platformAuthSecret == null) {
      throw new InvalidConfigException(
          "Please provide the secret before encoding JWT for API Authentication");
    }

    Instant timeExp = Instant.ofEpochSecond(token.getExp());
    Instant timeIat = Instant.ofEpochSecond(token.getIat());
    JwtBuilder builder = Jwts.builder().setIssuedAt(from(timeIat)).setExpiration(from(timeExp));

    return builder.signWith(SignatureAlgorithm.HS256, secret).compact();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public <T extends AbstractJwt> T decode(String cipher, Class<T> cls)
      throws NotSupportedException, NoSuchMethodException, InvocationTargetException,
          InstantiationException, IllegalAccessException {
    String secret;
    if (cls.equals(Sep10Jwt.class)) {
      secret = sep10JwtSecret;
    } else if (cls.equals(Sep24InteractiveUrlJwt.class)) {
      secret = sep24InteractiveUrlJwtSecret;
    } else if (cls.equals(Sep24MoreInfoUrlJwt.class)) {
      secret = sep24MoreInfoUrlJwtSecret;
    } else if (cls.equals(CallbackAuthJwt.class)) {
      secret = callbackAuthSecret;
    } else if (cls.equals(PlatformAuthJwt.class)) {
      secret = platformAuthSecret;
    } else if (cls.equals(CustodyAuthJwt.class)) {
      secret = custodyAuthSecret;
    } else {
      throw new NotSupportedException(
          String.format("The Jwt class:[%s] is not supported", cls.getName()));
    }

    JwtParser jwtParser = Jwts.parser();
    jwtParser.setSigningKey(secret);
    Jwt jwt = jwtParser.parseClaimsJws(cipher);
    Header header = jwt.getHeader();
    if (!(header instanceof DefaultJwsHeader)) {
      // This should not happen
      throw new IllegalArgumentException("Bad token");
    }
    DefaultJwsHeader defaultHeader = (DefaultJwsHeader) header;
    if (!defaultHeader.getAlgorithm().equals(SignatureAlgorithm.HS256.getValue())) {
      // Not signed by the JWTService.
      throw new IllegalArgumentException("Bad token");
    }

    if (cls.equals(Sep10Jwt.class)) {
      return (T) Sep10Jwt.class.getConstructor(Jwt.class).newInstance(jwt);
    } else if (cls.equals(Sep24InteractiveUrlJwt.class)) {
      return (T) Sep24InteractiveUrlJwt.class.getConstructor(Jwt.class).newInstance(jwt);
    } else if (cls.equals(Sep24MoreInfoUrlJwt.class)) {
      return (T) Sep24MoreInfoUrlJwt.class.getConstructor(Jwt.class).newInstance(jwt);
    } else if (cls.equals(PlatformAuthJwt.class)) {
      return (T) PlatformAuthJwt.class.getConstructor(Jwt.class).newInstance(jwt);
    } else if (cls.equals(CustodyAuthJwt.class)) {
      return (T) CustodyAuthJwt.class.getConstructor(Jwt.class).newInstance(jwt);
    } else {
      return (T) CallbackAuthJwt.class.getConstructor(Jwt.class).newInstance(jwt);
    }
  }

  private String toBase64OrNull(String value) {
    return value == null ? null : Base64.encodeBase64String(value.getBytes(StandardCharsets.UTF_8));
  }
}
