/*
 * Copyright 2020 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package tech.pegasys.eth2signer.tests;

import static io.restassured.RestAssured.given;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.singletonMap;
import static org.hamcrest.text.IsEqualIgnoringCase.equalToIgnoringCase;

import tech.pegasys.artemis.bls.BLS;
import tech.pegasys.artemis.bls.BLSKeyPair;
import tech.pegasys.artemis.bls.BLSPublicKey;
import tech.pegasys.artemis.bls.BLSSecretKey;
import tech.pegasys.artemis.bls.BLSSignature;
import tech.pegasys.eth2signer.dsl.HashicorpSigningParams;
import tech.pegasys.eth2signer.dsl.signer.SignerConfigurationBuilder;
import tech.pegasys.eth2signer.dsl.utils.MetadataFileHelpers;
import tech.pegasys.signers.bls.keystore.model.KdfFunction;
import tech.pegasys.signers.hashicorp.dsl.DockerClientFactory;
import tech.pegasys.signers.hashicorp.dsl.HashicorpNode;

import java.nio.file.Path;

import io.restassured.http.ContentType;
import io.vertx.core.json.JsonObject;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

public class KeyLoadAndSignAcceptanceTest extends AcceptanceTestBase {

  private static final Bytes SIGNING_ROOT = Bytes.wrap("Hello, world!".getBytes(UTF_8));
  private static final String PRIVATE_KEY =
      "3ee2224386c82ffea477e2adf28a2929f5c349165a4196158c7f3a2ecca40f35";

  private static final MetadataFileHelpers metadataFileHelpers = new MetadataFileHelpers();
  private static final BLSSecretKey key = BLSSecretKey.fromBytes(Bytes.fromHexString(PRIVATE_KEY));
  private static final BLSKeyPair keyPair = new BLSKeyPair(key);
  private static final BLSPublicKey publicKey = keyPair.getPublicKey();
  private static final BLSSignature expectedSignature =
      BLS.sign(keyPair.getSecretKey(), SIGNING_ROOT);
  private static final String SIGN_ENDPOINT = "/signer/sign/{publicKey}";

  @TempDir Path testDirectory;

  @Test
  public void signDataWithKeyLoadedFromUnencryptedFile() {
    final String configFilename = publicKey.toString().substring(2);
    final Path keyConfigFile = testDirectory.resolve(configFilename + ".yaml");
    metadataFileHelpers.createUnencryptedYamlFileAt(keyConfigFile, PRIVATE_KEY);

    final SignerConfigurationBuilder builder = new SignerConfigurationBuilder();
    builder.withKeyStoreDirectory(testDirectory);
    startSigner(builder.build());

    given()
        .baseUri(signer.getUrl())
        .filter(getOpenApiValidationFilter())
        .contentType(ContentType.JSON)
        .pathParam("publicKey", keyPair.getPublicKey().toString())
        .body(new JsonObject().put("signingRoot", SIGNING_ROOT.toHexString()).toString())
        .when()
        .post(SIGN_ENDPOINT)
        .then()
        .assertThat()
        .statusCode(200)
        .body(equalToIgnoringCase(expectedSignature.toString()));
  }

  @ParameterizedTest
  @EnumSource(KdfFunction.class)
  public void signDataWithKeyLoadedFromKeyStoreFile(KdfFunction kdfFunction) {
    final String configFilename = publicKey.toString().substring(2);

    final Path keyConfigFile = testDirectory.resolve(configFilename + ".yaml");
    metadataFileHelpers.createKeyStoreYamlFileAt(keyConfigFile, keyPair, kdfFunction);

    final SignerConfigurationBuilder builder = new SignerConfigurationBuilder();
    builder.withKeyStoreDirectory(testDirectory);
    startSigner(builder.build());

    given()
        .baseUri(signer.getUrl())
        .filter(getOpenApiValidationFilter())
        .contentType(ContentType.JSON)
        .pathParam("publicKey", keyPair.getPublicKey().toString())
        .body(new JsonObject().put("signingRoot", SIGNING_ROOT.toHexString()).toString())
        .when()
        .post(SIGN_ENDPOINT)
        .then()
        .assertThat()
        .statusCode(200)
        .body(equalToIgnoringCase(expectedSignature.toString()));
  }

  @Test
  public void receiveA404IfRequestedKeyDoesNotExist() {
    final SignerConfigurationBuilder builder = new SignerConfigurationBuilder();
    startSigner(builder.build());

    given()
        .baseUri(signer.getUrl())
        .filter(getOpenApiValidationFilter())
        .contentType(ContentType.JSON)
        .pathParam("publicKey", keyPair.getPublicKey().toString())
        .body(new JsonObject().put("signingRoot", SIGNING_ROOT.toHexString()).toString())
        .when()
        .post(SIGN_ENDPOINT)
        .then()
        .assertThat()
        .statusCode(404);
  }

  @Test
  public void receiveA400IfSigningRootIsMissingFromJsonBody() {
    final String configFilename = keyPair.getPublicKey().toString().substring(2);
    final Path keyConfigFile = testDirectory.resolve(configFilename + ".yaml");
    metadataFileHelpers.createUnencryptedYamlFileAt(keyConfigFile, PRIVATE_KEY);

    final SignerConfigurationBuilder builder = new SignerConfigurationBuilder();
    builder.withKeyStoreDirectory(testDirectory);
    startSigner(builder.build());

    // without OpenAPI validation filter
    given()
        .baseUri(signer.getUrl())
        .contentType(ContentType.JSON)
        .pathParam("publicKey", keyPair.getPublicKey().toString())
        .body("{\"invalid\": \"json body\"}")
        .when()
        .post(SIGN_ENDPOINT)
        .then()
        .assertThat()
        .statusCode(400);
  }

  @Test
  public void receiveA400IfJsonBodyIsMalformed() {
    final String configFilename = keyPair.getPublicKey().toString().substring(2);
    final Path keyConfigFile = testDirectory.resolve(configFilename + ".yaml");
    metadataFileHelpers.createUnencryptedYamlFileAt(keyConfigFile, PRIVATE_KEY);

    final SignerConfigurationBuilder builder = new SignerConfigurationBuilder();
    builder.withKeyStoreDirectory(testDirectory);
    startSigner(builder.build());

    // without OpenAPI validation filter
    given()
        .baseUri(signer.getUrl())
        .contentType(ContentType.JSON)
        .pathParam("publicKey", keyPair.getPublicKey().toString())
        .body("not a json body")
        .when()
        .post(SIGN_ENDPOINT)
        .then()
        .assertThat()
        .statusCode(400);
  }

  @Test
  public void unusedFieldsInRequestDoesNotAffectSigning() {
    final String configFilename = keyPair.getPublicKey().toString().substring(2);
    final Path keyConfigFile = testDirectory.resolve(configFilename + ".yaml");
    metadataFileHelpers.createUnencryptedYamlFileAt(keyConfigFile, PRIVATE_KEY);

    final SignerConfigurationBuilder builder = new SignerConfigurationBuilder();
    builder.withKeyStoreDirectory(testDirectory);
    startSigner(builder.build());

    given()
        .baseUri(signer.getUrl())
        .filter(getOpenApiValidationFilter())
        .contentType(ContentType.JSON)
        .pathParam("publicKey", keyPair.getPublicKey().toString())
        .body(
            new JsonObject()
                .put("signingRoot", SIGNING_ROOT.toHexString())
                .put("unknownField", "someValue")
                .toString())
        .when()
        .post(SIGN_ENDPOINT)
        .then()
        .assertThat()
        .statusCode(200)
        .body(equalToIgnoringCase(expectedSignature.toString()));
  }

  @Test
  public void ableToSignUsingHashicorp() {
    final String configFilename = keyPair.getPublicKey().toString().substring(2);
    final DockerClientFactory dockerClientFactory = new DockerClientFactory();
    final HashicorpNode hashicorpNode =
        HashicorpNode.createAndStartHashicorp(dockerClientFactory.create(), true);
    try {
      final String secretPath = "acceptanceTestSecretPath";
      final String secretName = "secretName";

      hashicorpNode.addSecretsToVault(singletonMap(secretName, PRIVATE_KEY), secretPath);

      final Path keyConfigFile = testDirectory.resolve(configFilename + ".yaml");
      metadataFileHelpers.createHashicorpYamlFileAt(
          keyConfigFile, new HashicorpSigningParams(hashicorpNode, secretPath, secretName));

      final SignerConfigurationBuilder builder = new SignerConfigurationBuilder();
      builder.withKeyStoreDirectory(testDirectory);
      startSigner(builder.build());

      given()
          .baseUri(signer.getUrl())
          .filter(getOpenApiValidationFilter())
          .contentType(ContentType.JSON)
          .pathParam("publicKey", keyPair.getPublicKey().toString())
          .body(
              new JsonObject()
                  .put("signingRoot", SIGNING_ROOT.toHexString())
                  .put("unknownField", "someValue")
                  .toString())
          .when()
          .post(SIGN_ENDPOINT)
          .then()
          .assertThat()
          .statusCode(200)
          .body(equalToIgnoringCase(expectedSignature.toString()));
    } finally {
      hashicorpNode.shutdown();
    }
  }
}
