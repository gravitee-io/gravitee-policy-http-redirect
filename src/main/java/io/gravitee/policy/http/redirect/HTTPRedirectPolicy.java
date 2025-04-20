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

import static java.util.stream.Collectors.toMap;

import io.gravitee.el.TemplateEngine;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.Response;
import io.gravitee.gateway.api.http.HttpHeaderNames;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.gravitee.gateway.reactive.api.policy.http.HttpPolicy;
import io.gravitee.node.api.cache.CacheConfiguration;
import io.gravitee.node.plugin.cache.common.InMemoryCache;
import io.gravitee.policy.api.PolicyChain;
import io.gravitee.policy.api.PolicyResult;
import io.gravitee.policy.api.annotations.OnRequest;
import io.gravitee.policy.http.redirect.configuration.HTTPRedirectPolicyConfiguration;
import io.gravitee.policy.http.redirect.configuration.HTTPRedirectPolicyConfiguration.Rule;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;

/**
 * @author Antoine CORDIER (antoine.cordier at graviteesource.com)
 * @author GraviteeSource Team
 */
@Slf4j
public class HTTPRedirectPolicy implements HttpPolicy {

    private static final String GROUP_TEMPLATE_ATTRIBUTE = "group";
    private static final String GROUP_NAME_ATTRIBUTE = "groupName";
    private static final String INTERRUPT_KEY = "REQUEST_REDIRECTED";

    private final HTTPRedirectPolicyConfiguration configuration;

    private final InMemoryCache<String, Pattern> cache;

    public HTTPRedirectPolicy(HTTPRedirectPolicyConfiguration configuration) {
        this.configuration = configuration;
        this.cache = new InMemoryCache<>(id(), cacheConfiguration(configuration.cache()));
    }

    @Override
    public String id() {
        return "policy-http-redirect";
    }

    @Override
    public Completable onRequest(final HttpPlainExecutionContext ctx) {
        return rxFindMatchingRule(ctx.request().pathInfo(), ctx.getTemplateEngine())
            .flatMap(ruleMatch -> rxBuildRedirect(ruleMatch, ctx.getTemplateEngine()))
            .flatMapCompletable(redirect -> rxSendRedirect(redirect, ctx));
    }

    private Maybe<RuleMatch> rxFindMatchingRule(String path, TemplateEngine templateEngine) {
        return Flowable
            .fromIterable(configuration.rules())
            .flatMapMaybe(rule ->
                templateEngine
                    .eval(rule.path(), String.class)
                    .map(this::compile)
                    .map(pattern -> new RuleMatch(rule, pattern, pattern.matcher(path)))
                    .filter(ruleMatch -> ruleMatch.matcher.matches())
            )
            .firstElement();
    }

    private Maybe<Redirect> rxBuildRedirect(RuleMatch ruleMatch, TemplateEngine templateEngine) {
        templateEngine.getTemplateContext().setVariable(GROUP_TEMPLATE_ATTRIBUTE, getIndexedGroups(ruleMatch));
        templateEngine.getTemplateContext().setVariable(GROUP_NAME_ATTRIBUTE, getNamedGroups(ruleMatch));
        return templateEngine
            .eval(ruleMatch.rule.location(), String.class)
            .map(location -> new Redirect(location, ruleMatch.rule.status()));
    }

    private Completable rxSendRedirect(Redirect redirect, HttpPlainExecutionContext ctx) {
        ctx.response().status(redirect.status).headers().set(HttpHeaderNames.LOCATION, redirect.location);
        return ctx.interrupt();
    }

    @OnRequest
    public void onRequest(Request request, Response response, ExecutionContext executionContext, PolicyChain policyChain) {
        findMatchingRule(request.pathInfo(), executionContext.getTemplateEngine())
            .map(ruleMatch -> buildRedirect(ruleMatch, executionContext.getTemplateEngine()))
            .ifPresentOrElse(redirect -> sendRedirect(redirect, response, policyChain), () -> policyChain.doNext(request, response));
    }

    private Optional<RuleMatch> findMatchingRule(String path, TemplateEngine templateEngine) {
        for (var rule : configuration.rules()) {
            var pattern = compile(templateEngine.convert(rule.path()));
            var matcher = pattern.matcher(path);
            if (matcher.matches()) {
                return Optional.of(new RuleMatch(rule, pattern, matcher));
            }
        }
        return Optional.empty();
    }

    private Pattern compile(String path) {
        if (cache.containsKey(path)) {
            return cache.get(path);
        }
        var pattern = Pattern.compile(path);
        cache.put(path, pattern);
        return pattern;
    }

    private Redirect buildRedirect(RuleMatch ruleMatch, TemplateEngine templateEngine) {
        templateEngine.getTemplateContext().setVariable(GROUP_TEMPLATE_ATTRIBUTE, getIndexedGroups(ruleMatch));
        templateEngine.getTemplateContext().setVariable(GROUP_NAME_ATTRIBUTE, getNamedGroups(ruleMatch));
        return new Redirect(templateEngine.getValue(ruleMatch.rule.location(), String.class), ruleMatch.rule.status());
    }

    private void sendRedirect(Redirect redirect, Response response, PolicyChain policyChain) {
        response.headers().set(HttpHeaderNames.LOCATION, redirect.location);
        policyChain.failWith(PolicyResult.failure(INTERRUPT_KEY, redirect.status, ""));
    }

    private static String[] getIndexedGroups(RuleMatch ruleMatch) {
        var groups = new String[ruleMatch.matcher.groupCount()];
        IntStream.range(0, ruleMatch.matcher.groupCount()).forEach(i -> groups[i] = ruleMatch.matcher.group(i + 1));
        return groups;
    }

    private static Map<String, String> getNamedGroups(RuleMatch ruleMatch) {
        return ruleMatch.pattern
            .namedGroups()
            .entrySet()
            .stream()
            .collect(toMap(Map.Entry::getKey, entry -> ruleMatch.matcher.group(entry.getKey())));
    }

    private static CacheConfiguration cacheConfiguration(HTTPRedirectPolicyConfiguration.Cache config) {
        if (config == null) {
            return CacheConfiguration.builder().build();
        }
        return CacheConfiguration.builder().maxSize(config.maxSize()).timeToLiveInMs(config.timeToLive()).build();
    }

    private record Redirect(String location, int status) {}

    private record RuleMatch(Rule rule, Pattern pattern, Matcher matcher) {}
}
