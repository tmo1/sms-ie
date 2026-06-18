# Changelog

Going forward, all notable changes to this project will be documented in this file. This file is, however, a relatively recent addition to the project, and does not (yet) document changes prior to v2.8.0.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). We try to follow some, but not all, of the rules and recommendations of [Common Changelog](https://common-changelog.org).

This project attempts to adhere to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [2.10.1] - 2026-06-18

This release does not change anything from [release 2.10.0](https://github.com/tmo1/sms-ie/releases/tag/v2.10.0), but was created since the uploaded build of v2.10.0 is broken ([issue #347](https://github.com/tmo1/sms-ie/issues/347), [issue #348](https://github.com/tmo1/sms-ie/issues/348))

## [2.10.0] - 2026-06-17

### Changed

 - Bump `compileSdk` and `targetSdkVersion` to 37 ([16fe214](https://github.com/tmo1/sms-ie/commit/16fe21485bcc933d6dd7a22e19791618f2fc65c7))
 
### Added

 - Add support for ISO 8601 dates in message filters ([7623731](https://github.com/tmo1/sms-ie/commit/76237312b6fc2c118a0c6c1d1b48ca425b9024d4))
 - Add settings to exclude user specified addresses from calls to [`getOrCreateThreadId`](https://developer.android.com/reference/android/provider/Telephony.Threads#getOrCreateThreadId(android.content.Context,%20java.util.Set%3Cjava.lang.String%3E)) and (optionally) from insertion into the address table when importing MMS messages ([issue #275](https://github.com/tmo1/sms-ie/issues/275))
 - Add Slovak translation ([7b3ccef](https://github.com/tmo1/sms-ie/commit/7b3ccef76ca48e8f79ca87191f309e600af1eef5), [840abf8](https://github.com/tmo1/sms-ie/commit/840abf825836e69d73d83bd3835564e476366308)) (Peter Vágner)
 - Add Indonesian translation ([3307ebd](https://github.com/tmo1/sms-ie/commit/3307ebd926d2e48905d727ae4551594e3f71f6c0)) (Arif Budiman)

### Fixed

 - Retry throttled notifications when idle. This fixes an issue where notifications that change infrequently are
sometimes never shown. ([e3db6d2](https://github.com/tmo1/sms-ie/commit/e3db6d21504ffbd7f1f99995cfe4a70686377f04)) (Andrew Gunnerson)
 - Update Chinese (Simplified Han script) translation ([86ca934](https://github.com/tmo1/sms-ie/commit/86ca934f3b9b630da91b430eafd527d50db33e3a), [5caa952](https://github.com/tmo1/sms-ie/commit/5caa9528a92d2eff0e0727856420bfdce85d1318)) (大王叫我来巡山)
 - Update Polish translation ([a5a51fb](https://github.com/tmo1/sms-ie/commit/a5a51fb58e1c81abc50b3c67e6ac4d7d0bc377bd)) (rehork)
 - Update French translation ([cdb336b](https://github.com/tmo1/sms-ie/commit/cdb336b5f1dcf3e6147efcbad3d1f6099b459057)) (MarcMush)
 - Update Italian translation ([99daf11](https://github.com/tmo1/sms-ie/commit/99daf11c4c5c48dbd5e97dbb75bd9dc47fd62979)) (Random)
 - Update German translation ([6cc6908](https://github.com/tmo1/sms-ie/commit/6cc690853ef95d3c0a14a96dce1f7df60cfb5381)) (nautilusx)
 - Update Turkish translation ([e316462](https://github.com/tmo1/sms-ie/commit/e3164622eda3d1b074f25b382be544f82cf10b55)) (baturax)

## [2.9.0] - 2026-03-23

### Changed

 - **Breaking:** Apply message filtering to wipe operations ([issue #237](https://github.com/tmo1/sms-ie/issues/237))
 
### Added

 - Add count messages operation ([issue #237](https://github.com/tmo1/sms-ie/issues/237))
 
### Fixed

 - Don't insert into non-existent MMS address columns upon import ([issue #322](https://github.com/tmo1/sms-ie/issues/322))
 - Cancel any scheduled exports when the `Enable scheduled export` preference is disabled ([issue #326](https://github.com/tmo1/sms-ie/issues/326))
 - Remove `android.permission.ACCESS_NETWORK_STATE` ([issue #324](https://github.com/tmo1/sms-ie/issues/324))
 - Update Italian translation ([c001e65](https://github.com/tmo1/sms-ie/commit/c001e656f539656d7afac5b85ecbedb3e96b6ead), [ae76174](https://github.com/tmo1/sms-ie/commit/ae7617428457a2e35c1f8d3b68fb25033d552ac1)) (Random)

## [2.8.0] - 2026-01-04

### Changed

 - Bump standard flavor `minSdkVersion` to 23 ([1f89ab6](https://github.com/tmo1/sms-ie/commit/1f89ab6edc268dd6b23f4d6c492736f6a5d3fac9))
 
### Fixed
 
 - Handle MMS `BLOB` data without crashing during export. Such data can either be included in the export (the default) or skipped, controllable by a settings toggle (issues [#87](https://github.com/tmo1/sms-ie/issues/87), [#320](https://github.com/tmo1/sms-ie/issues/320))
 - Cancel persistent notification when not using foreground service ([PR #300](https://github.com/tmo1/sms-ie/pull/300)) (Andrew Gunnerson)
 - Fix text readability problems in the `Message Filters` activity ([PR #305](https://github.com/tmo1/sms-ie/pull/305)) (Andrew Gunnerson)
 - Update Portuguese translation ([8ffd01d](https://github.com/tmo1/sms-ie/commit/8ffd01d209837e572ba1679e486f0c55e3da2dcd)) (maverick74)
 - Update German translation ([9acc12a](https://github.com/tmo1/sms-ie/commit/9acc12ab0ca1c6811340778a4ea6e007b50fa744), [6e75d5e](https://github.com/tmo1/sms-ie/commit/6e75d5e70848ef22476e18645f5fd706b7ca7355)) (nautilusx, Atalanttore)
 - Update Russian translation ([c9ff55d](https://github.com/tmo1/sms-ie/commit/c9ff55db558f2cac15a20bee218127ce8909ff2b)) (Axus Wizix)
 - Update Chinese (Traditional Han script) translation ([b0897e6](https://github.com/tmo1/sms-ie/commit/b0897e6affa7790733400d7041cb81a3cb0063b3)) (Unknownman820)
 - Update French translation ([bfefcae](https://github.com/tmo1/sms-ie/commit/bfefcae66ab75e1d42d371e6bd5b3f68c7703e22), [3b978f0](https://github.com/tmo1/sms-ie/commit/3b978f0416ce9e3eaada3f23215f0991f9a0a14f), [c24cd22](https://github.com/tmo1/sms-ie/commit/c24cd22ff93779422e315723ae514496179db4c3)) (MarcMush)
 - Update Polish translation ([7318290](https://github.com/tmo1/sms-ie/commit/7318290720f6932c5872bb5b34aca9b89fc19ec1), [ae3f397](https://github.com/tmo1/sms-ie/commit/ae3f3979ba8844a160690e51687d3b266c0c8070)) (rehork)
 - Update Danish translation ([364fd9d](https://github.com/tmo1/sms-ie/commit/364fd9ddafd1322de5ad7a7ce37864e9707d69c1)) (catsnote)

[2.8.0]: https://github.com/tmo1/sms-ie/releases/tag/v2.8.0
[2.9.0]: https://github.com/tmo1/sms-ie/releases/tag/v2.9.0
[2.10.0]: https://github.com/tmo1/sms-ie/releases/tag/v2.10.0
[2.10.1]: https://github.com/tmo1/sms-ie/releases/tag/v2.10.1
[Unreleased]: https://github.com/tmo1/sms-ie/compare/v2.10.1...HEAD
