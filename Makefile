export npm_config_cache := /tmp/amazon-devpost-cleanrep/npm
.PHONY: setup test core-test phone-test web-test lint typecheck build browser-test demo server tv-build tv-install phone-build phone-install lan-relay
setup:
	npm --prefix apps/web ci
	$(MAKE) build
# Every check that needs no device: the server, the phone app's network rules and TLS pinning, and the web pages.
test: core-test phone-test web-test
# The TV app's server (apps/core): Java 8 API, no dependencies; FILTER=<text> runs a subset.
core-test:
	bash scripts/core.sh $(FILTER)
phone-test:
	bash scripts/phone-test.sh
web-test:
	npm --prefix apps/web test
lint:
	npm --prefix apps/web run format:check
typecheck:
	npm --prefix apps/web run typecheck
build:
	npm --prefix apps/web run build
# The ten browser journeys against the server the TV app runs; results in evals/browser-results.json.
browser-test: build
	bash scripts/core.sh --compile-only
	npm --prefix apps/web run test:browser
# A desktop-browser demo with no devices: the TV at http://127.0.0.1:8105/tv, a replayed phone at
# http://127.0.0.1:8105/phone?source=replay&fixture=squat_shallow_10 (README). Loopback only; data in var/demo-data.
demo: build
	bash scripts/core.sh --compile-only
	java -Dcleanrep.root=. -cp apps/core/build/classes org.cleanrep.core.Main --data var/demo-data --allow-replay
# The TV app's server on this computer, for a phone test without a TV: the TV page at http://127.0.0.1:8105/tv, phones
# over pinned HTTPS on 8443. The ready line prints the pairing link with the certificate pin.
server: build
	bash scripts/core.sh --compile-only
	java -Dcleanrep.root=. -cp apps/core/build/classes org.cleanrep.core.Main --host 0.0.0.0 --https-port 8443
# Debug APKs. tv-build also builds the phone APK and packs it into the TV app, which offers it to phones.
tv-build:
	bash scripts/tv-build.sh
tv-install:
	bash scripts/tv-install.sh
phone-build:
	bash scripts/phone-build.sh
phone-install:
	bash scripts/phone-install.sh
# The TV app on the emulator, reachable on this computer's Wi-Fi by a phone with no debugging (device test 3b).
# Needs `adb forward` of 8105 and 8443 first; HOST=<wifi-ip> picks the address (default: the Wi-Fi one).
lan-relay:
	node scripts/lan-relay.mjs $(HOST)
