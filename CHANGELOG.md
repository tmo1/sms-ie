# Changelog

Going forward, all notable changes to this project will be documented in this file. This file is, however, a relatively recent addition to the project, and does not (yet) document changes prior to v2.8.0.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). We try to follow some, but not all, of the rules and recommendations of [Common Changelog](https://common-changelog.org).

This project attempts to adhere to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/tmo1/sms-ie/compare/v2.8.0...HEAD
[2.8.0]: https://github.com/tmo1/sms-ie/releases/tag/v2.8.0
[2.9.0]: https://github.com/tmo1/sms-ie/releases/tag/v2.9.0
