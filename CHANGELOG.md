# Changelog

Going forward, all notable changes to this project will be documented in this file. This file is, however, a relatively recent addition to the project, and does not (yet) document changes prior to v2.8.0.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). We try to follow some, but not all, of the rules and recommendations of [Common Changelog](https://common-changelog.org).

This project attempts to adhere to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

 - **Breaking:** Bump standard flavor `minSdkVersion` to 24 ([e8b9a16](https://github.com/tmo1/sms-ie/commit/e8b9a163d366df4b69ccf85e1bdb7b660f1b1228))
 - Switch to unified `versionCode` for both `standard` and `legacy` app flavors ([10d8c30](https://github.com/tmo1/sms-ie/commit/10d8c3084b0c91fcd12e28046c2471b7e4128507))

### Added

 - Add option (on by default) to try to avoid compressing relatively incompressible data when exporting MMS messages (see the "Compression" section of the README for more information) ([PR #377](https://github.com/tmo1/sms-ie/pull/377)) (DustinReynoldsPE)
 - Implement various performance improvements ([PR #376](https://github.com/tmo1/sms-ie/pull/376), [PR #381](https://github.com/tmo1/sms-ie/pull/381)) (DustinReynoldsPE, Andrew Gunnerson)
 - Overhaul scheduled export framework: Add "Export interval (days):" setting and display dates and times of last successful scheduled export and next scheduled export ([3ae06db](https://github.com/tmo1/sms-ie/commit/3ae06db221293844f52d55b1fd8f69ca6401c214)) (tmo1, Apflkuacha)
 - Add an in-app warning when the app cannot access restricted messages, which will prevent the export of encrypted RCS messages (see the README for details of the problem and solutions thereto) ([64bbadd](https://github.com/tmo1/sms-ie/commit/64bbaddd9eaf736acf8fd9a083730e83f3f8a90f)) (Andrew Gunnerson, tmo1)
 - Add Czech translation ([511a7ff](https://github.com/tmo1/sms-ie/commit/511a7fff529f8138e06e9ff66db8316c051c4937)) (cz-vilda)
 
### Fixed
 
 - Update Chinese (Simplified Han script) translation ([929ffa8](https://github.com/tmo1/sms-ie/commit/929ffa88731f8d45a6d9c1d7fea2f5121eb1a869)) (Crystal RainSlide)
 - Update German translation ([0b5fc33](https://github.com/tmo1/sms-ie/commit/0b5fc33b0a901fffedc12b80f9a59d1b9a7d7d76), [d062b58](https://github.com/tmo1/sms-ie/commit/d062b584a14dfcf6439199273e140990faa1051a)) (Atalanttore, nautilusx)
 
## [2.11.1] - 2026-07-31

### Fixed

 - Add ProGuard rules necessary for JNA to run without crashing ([issue #357](https://github.com/tmo1/sms-ie/issues/357), [PR #358](https://github.com/tmo1/sms-ie/pull/358)) (Andrew Gunnerson)
 - Update Italian translation ([d068542](https://github.com/tmo1/sms-ie/commit/d068542aa452c7e5727c43a8fb67d43dcdb1ae6c)) (Random)

 
## [2.11.0] - 2026-07-28

**Note:** While this release does not introduce any known breaking changes to previously documented functionality, it does contain substantial architectural changes and code refactorization that were introduced in the course of adding support for encryption and decryption, and these may have introduced bugs; please report any bugs encountered.

### Added

 - Add optional authenticated encryption and decryption on devices with API level >= 21 (Android 6) ([aa40268](https://github.com/tmo1/sms-ie/commit/aa402689b1b54e0a1e91b100e0f4d6d4dfd9385d), [71eafbc](https://github.com/tmo1/sms-ie/commit/71eafbcf3231b36ecb890d91005307517022f1bd), [517d69c](https://github.com/tmo1/sms-ie/commit/517d69c5b3a5a0f6c76020c89b7a5a120a55113c))
 - Update Turkish translation ([6634678](https://github.com/tmo1/sms-ie/commit/66346789b2defdcda26bd5a0c6ecc925d2afc039), [0f4bb2f](https://github.com/tmo1/sms-ie/commit/0f4bb2fa6a4af264fdae441937b0f42f12446594)) (Oğuz Ersen)
 - Update Chinese (Simplified Han script) translation ([322b560](https://github.com/tmo1/sms-ie/commit/322b5605fb30e2ee5f9ac085626e134b15b47c3b)) (Hosted Weblate user 54392)
 - Update Polish translation ([35e1097](https://github.com/tmo1/sms-ie/commit/35e109700e55a2de3d737dce51473516d90e05ff), [3f7071b](https://github.com/tmo1/sms-ie/commit/3f7071b43ec92942e74216ffac53483a9c48533e)) (rehork, NooB9496)

## [2.10.2] - 2026-06-18

This release does not change anything from [release 2.10.1](https://github.com/tmo1/sms-ie/releases/tag/v2.10.1), but was created since the uploaded build of v2.10.1 is (also) broken ([issue #347](https://github.com/tmo1/sms-ie/issues/347))

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
[2.10.2]: https://github.com/tmo1/sms-ie/releases/tag/v2.10.2
[2.11.0]: https://github.com/tmo1/sms-ie/releases/tag/v2.11.0
[2.11.1]: https://github.com/tmo1/sms-ie/releases/tag/v2.11.1
[Unreleased]: https://github.com/tmo1/sms-ie/compare/v2.11.1...HEAD
