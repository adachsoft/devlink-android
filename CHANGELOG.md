# Changelog

All notable changes to this project will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]
### Added
- Added a native Android LAN agent with a foreground HTTP service, private/local source filtering, stable device identity, NSD discovery, and a bearer token stored for host access.
- Added the devlink v0.1.0 core endpoints and enabled `device.battery` extension, with extension routing and RFC 9457 problem responses.
- Added the Android app scaffold, build workflow, and bundled devlink v0.1.0 API specification.
- Added on-device SharedPreferences controls for each extension. Sensitive `android.adb` and `power.keep-awake` extensions are disabled by default, can be explicitly enabled on the device, and are reflected dynamically in routes and status.
- Added the `android.adb` extension for wireless debugging and enable-on-boot, with secure-settings permission checks and an NSD port fallback.
- Added the `power.keep-awake` and `network.wifi` extensions, including best-effort Wi-Fi network requests and a high-performance Wi-Fi lock while the service runs.
- Added HTTP problem responses for malformed JSON and invalid requests, plus response mapping for accepted, bad-request, and conflict statuses.
- Added binary HTTP responses and extension query-parameter forwarding, including HTTP 429 responses with preserved headers.
- Added the sensitive `display.screenshot` extension with explicit in-app and Accessibility Settings opt-in, authenticated LAN capture through AccessibilityService, PNG/JPEG output, state reporting, parameter validation, and rate limiting.
- Documented screenshot enablement and file-saving curl examples, and added fake-capturer unit tests.
### Fixed
- Wait for an IPv4 Wi-Fi connection before enabling wireless debugging on boot, and make at most one enable attempt per connection after Wi-Fi reconnects.
- Refresh the foreground notification and re-register the devlink NSD service when Wi-Fi connectivity or its IP address changes, without duplicate registrations.
- Return a null ADB port and clear discovered ADB port data while wireless debugging is disabled; clear the discovered port cache when the Wi-Fi IP changes.

