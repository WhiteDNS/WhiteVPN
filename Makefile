GRADLE ?= ./gradlew

.PHONY: test debug libbox release clean

test:
	$(GRADLE) test

debug:
	$(GRADLE) assembleDebug

libbox:
	./scripts/build-libbox.sh

release:
	./scripts/release.sh

clean:
	$(GRADLE) clean
