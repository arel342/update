# Nodelix Mobile — HTTP-only migration report

## Scope implemented in source

- REST endpoint generation is fixed to `http://` through the only `ProtocolType` value.
- Realtime endpoint generation is fixed to `ws://`.
- HTTPS/WSS requests are rejected by both application and per-network-hop guards.
- HTTP is restricted to the configured private/LAN host. Public hosts are rejected.
- OkHttp uses a private-LAN DNS resolver that rejects a hostname if any resolved address is public; the resolved private addresses are the addresses used by the connection.
- Android cleartext networking is enabled in the manifest; the previous network-security XML and custom certificate manager were removed.
- Custom CA PEM profile storage and UI fields were removed.
- Old saved profiles whose protocol was `HTTPS` are deserialized as `HTTP`; obsolete CA/cleartext fields are ignored by the app JSON configuration.
- Session storage, Android Keystore, AES-GCM vault, ECDSA P-256 signing and challenge-response implementation were not removed or rewritten.
- GitHub's Android release API check was disabled because that endpoint is HTTPS-only. The app reports this explicitly; server-side update checks through the configured LAN API remain separate.

## Security and compatibility constraints

HTTP does not encrypt requests or responses. Passwords, session cookies, Bearer tokens, signatures, and server responses are visible to an observer on the network and can be modified in transit. LAN host checks and signed challenges do not provide transport confidentiality or authenticated server responses. Use only a trusted LAN or a separately encrypted private tunnel.

Backend source compatibility was checked against `arel342/NodelixcoreOS` on `main`: `server.js` creates the listener with `http.createServer(app)`, and `mobile-auth.js` normalizes both `http:` and `https:` origins and includes the plain-HTTP request origin among challenge candidates. Therefore the mobile ECDSA canonical origin `http://host:port` is accepted by the current backend source. A live device-to-server login and WebSocket integration test was not run.

The Gradle Wrapper distribution URL remains HTTPS intentionally: it is build-tool bootstrap infrastructure, not an application network endpoint. Replacing it with plaintext would weaken build integrity and is outside the app transport migration.

## Validation status

- Static source scan: completed for `app/src/main`, `app/src/test`, and `app/src/androidTest`; no HTTPS/WSS transport implementation, TLS manager, custom CA field, or GitHub HTTPS release request remains. The only HTTPS strings in app tests are negative/legacy-migration test fixtures.
- Unit tests: not run. The wrapper could not download Gradle 8.10.2 because `services.gradle.org` was unreachable in this environment.
- Android build/APK: not produced. Android SDK variables are not configured in this environment.
- Live backend login, session renewal, WebSocket, and OTA: not verified.
