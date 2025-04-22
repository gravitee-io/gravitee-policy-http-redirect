## Overview
The `http-redirect` policy is used to send redirect responses to an HTTP client as described in
[RFC7231](https://datatracker.ietf.org/doc/html/rfc7231#section-6.4).

The URI for redirection is sent back to the HTTP client using a `Location` header and a 3xx HTTP status code.




## Usage

You can configure multiple rules and their respective redirections relative to the
initial request path (excluding the API context path).

The first rule defined in the rule list that matches the request path will trigger a redirect response with the associated status.

In a rule, `path` and `location` both support [Gravitee Expression Language](https://documentation.gravitee.io/apim/getting-started/gravitee-expression-language) expressions. The `path` property
supports regular expression matching, which allows capturing groups that can be reused to build the location
URL using expression language.

Two variables are added to the expression language context when the policy runs:

- `group` identifies regular expression groups that are captured following their index (starting from 0).
- `groupName` identifies regular expression groups that are captured using named groups.

### Rules configuration

Redirections triggered by various rules are summarized in the following table:

| Rule path | Rule location | Rule status | Incoming path | Location Header | Status |
|---|---|---|:-------------:|:---:|:---:|
| `/headers` | `https://httpbin.org/headers` | 302 |   `/headers`    | `https://httpbin.org/headers` | 302 |
| `/status/(?<code>.*)` | `https://httpbin.org/status/{#groupName['code']}` | 301 |  `/status/201`  | `https://httpbin.org/status/201` | 301 |
| `/(.*)` | `https://httpbin.org/anything/{#group[0]}` | 308 |     `/foo`      | `https://httpbin.org/anything/foo` | 308 |
| `/(.*)` | `https://httpbin.org/anything/{#group[0]}` | 301 | `/foo/bar/baz`  | `https://httpbin.org/anything/foo/bar/baz` | 301 |

### Cache configuration

The policy caches regular expression patterns compiled from the incoming request path to alleviate the cost compilation has on response times. 

Cache configuration is optional. By default, the cache is configured to store an unlimited number of items with no expiration.

For statically defined paths, the number of items stored in the cache will be equal to the number of rules defined for the policy.

If expression language is used to build rule paths dynamically, configuring the cache allows control of how memory consumption will be impacted by the use of the cache.


