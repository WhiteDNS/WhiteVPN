GRADLE ?= ./gradlew

.PHONY: test debug libclash mihomo-core release clean

test:
	$(GRADLE) test

debug:
	$(GRADLE) assembleDebug

mihomo-core:
	./scripts/build-flclash-core.sh

libclash: mihomo-core

release:
	./scripts/release.sh

clean:
	$(GRADLE) clean
