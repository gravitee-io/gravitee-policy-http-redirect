/*
 * Copyright © 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.policy.http.redirect;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.apim.gateway.tests.sdk.AbstractPolicyTest;
import io.gravitee.apim.gateway.tests.sdk.annotations.DeployApi;
import io.gravitee.apim.gateway.tests.sdk.annotations.GatewayTest;
import io.gravitee.apim.gateway.tests.sdk.connector.EndpointBuilder;
import io.gravitee.apim.gateway.tests.sdk.connector.EntrypointBuilder;
import io.gravitee.definition.model.ExecutionMode;
import io.gravitee.gateway.api.http.HttpHeaderNames;
import io.gravitee.plugin.endpoint.EndpointConnectorPlugin;
import io.gravitee.plugin.endpoint.http.proxy.HttpProxyEndpointConnectorFactory;
import io.gravitee.plugin.entrypoint.EntrypointConnectorPlugin;
import io.gravitee.plugin.entrypoint.http.proxy.HttpProxyEntrypointConnectorFactory;
import io.gravitee.policy.http.redirect.configuration.HTTPRedirectPolicyConfiguration;
import io.vertx.core.http.HttpMethod;
import io.vertx.rxjava3.core.http.HttpClient;
import io.vertx.rxjava3.core.http.HttpClientRequest;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

/**
 * @author Antoine CORDIER (antoine.cordier at graviteesource.com)
 * @author GraviteeSource Team
 */
class HTTPRedirectPolicyIntegrationTest {

    private static class Parameters implements ArgumentsProvider {

        private record GivenPathToExpectedLocation(String path, String location, int status) {
            @Override
            public String toString() {
                return String.format("[%s] responds with location [%s] and status [%d]", path, location, status);
            }
        }

        @Override
        public Stream<Arguments> provideArguments(ExtensionContext context) {
            return Stream.of(
                args("/headers", "https://httpbin.org/headers", 302),
                args("/status/201", "https://httpbin.org/status/201", 301),
                args("/status/204", "https://httpbin.org/status/204", 301),
                args("/anything/foo", "https://httpbin.org/anything/foo", 308),
                args("/anything/bar", "https://httpbin.org/anything/bar", 308),
                args("/anything/baz", "https://httpbin.org/anything/baz", 308),
                args("/anything/foo/bar/baz", "https://httpbin.org/anything/foo/bar/baz", 308),
                args("/greetings", "https://wiremock.dev/greetings", 200)
            );
        }

        private static Arguments args(String path, String location, int status) {
            return Arguments.of(new GivenPathToExpectedLocation(path, location, status));
        }
    }

    private static String buildPath(String contextPath, String path) {
        return Path.of(contextPath, path).toString();
    }

    private abstract static class HTTPRedirectTest extends AbstractPolicyTest<HTTPRedirectPolicy, HTTPRedirectPolicyConfiguration> {

        abstract String contextPath();

        @BeforeEach
        void beforeEach() {
            wiremock.stubFor(get("/greetings").willReturn(ok().withHeader("Location", "https://wiremock.dev/greetings")));
        }

        @Override
        public void configureEntrypoints(Map<String, EntrypointConnectorPlugin<?, ?>> entrypoints) {
            entrypoints.putIfAbsent("http-proxy", EntrypointBuilder.build("http-proxy", HttpProxyEntrypointConnectorFactory.class));
        }

        @Override
        public void configureEndpoints(Map<String, EndpointConnectorPlugin<?, ?>> endpoints) {
            endpoints.putIfAbsent("http-proxy", EndpointBuilder.build("http-proxy", HttpProxyEndpointConnectorFactory.class));
        }
    }

    @Nested
    @GatewayTest
    @DeployApi({ "/apis/v4/redirect.json" })
    @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
    class V4 extends HTTPRedirectTest {

        @Override
        String contextPath() {
            return "/http-redirect/v4/";
        }

        @ArgumentsSource(Parameters.class)
        @ParameterizedTest(name = "{0}")
        void should_redirect(Parameters.GivenPathToExpectedLocation params, HttpClient client) throws InterruptedException {
            client
                .rxRequest(HttpMethod.GET, buildPath(contextPath(), params.path))
                .flatMap(HttpClientRequest::rxSend)
                .flatMapPublisher(response -> {
                    assertThat(response.statusCode()).isEqualTo(params.status);
                    assertThat(response.getHeader(HttpHeaderNames.LOCATION)).isEqualTo(params.location);
                    return response.toFlowable();
                })
                .test()
                .await()
                .assertComplete()
                .assertNoErrors();
        }
    }

    @Nested
    @GatewayTest(v2ExecutionMode = ExecutionMode.V4_EMULATION_ENGINE)
    @DeployApi({ "/apis/v2/redirect.json" })
    @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
    class V3Emulated extends V4 {

        @Override
        String contextPath() {
            return "/http-redirect/v2/";
        }
    }

    @Nested
    @GatewayTest(v2ExecutionMode = ExecutionMode.V3)
    @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
    class V3NotEmulated extends V3Emulated {}
}
