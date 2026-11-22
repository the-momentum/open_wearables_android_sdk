# Samsung Health Data SDK (local only)

The Samsung Health Data SDK is proprietary software licensed by Samsung. It is not part of this repository and is not covered by its license.

By default the SDK compiles against `:samsung-health-stub`, which declares only the Samsung API the SDK calls. Apps that use Samsung Health add the AAR they download from Samsung to their own app module.

To build and run the unit tests against the real SDK, download it from the [Samsung Health Data SDK page](https://developer.samsung.com/health/data/overview.html) and put the `.aar` file in this directory. Git ignores it, so it never gets committed.
