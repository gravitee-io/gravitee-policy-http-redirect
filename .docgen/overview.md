The `http-redirect` policy is used to send redirect responses to an HTTP client as described in
[RFC7231](https://datatracker.ietf.org/doc/html/rfc7231#section-6.4).

The URI for redirection is sent back to the HTTP client using a `Location` header and a 3xx HTTP status code.

