# Encryption

SMS I/E can optionally encrypt data upon export and decrypt it upon import. It uses [authenticated encryption](https://en.wikipedia.org/wiki/Authenticated_encryption), which guarantees (assuming the use of a sufficiently strong passphrase) both data confidentiality (an attacker cannot determine the plaintext) and authenticity (an attacker cannot alter the plaintext without the tampering being detected).

## App settings

* `Perform encryption and decryption for manual operations`: If this setting is enabled, all manual export operations will perform encryption and all import operations will perform decryption (and authentication).
* `Use stored passphrase for encryption and decryption for manual operations`: If this setting is enabled, all manual operations will use the stored passphrase for encryption or decryption; if it is disabled (the default), the app will prompt for a passphrase on each manual operation.
* `Perform encryption for scheduled operations (using stored passphrase)`: If this setting is enabled, all scheduled operations will perform encryption using the stored passphrase.
* `Store passphrase`: Tapping this button will prompt for a passphrase, which will then be stored. (There is no way to view the currently stored passphrase.)

## Passphrase creation and management

SMS I/E does not impose any limitations such as length or allowed characters (beyond any imposed by Android itself) on passphrases; it is entirely the responsibility of the user to provide secure passphrases. A detailed discussion of secure passphrase generation and management is beyond the scope of this document, but the use of a password manager or similar tool(s) for these tasks is strongly recommended. If the passphrase is insufficiently strong, an attacker may be able to brute force it; if the passphrase is lost, there is no way to recover the encrypted data (short of brute forcing the passphrase).

For reference:

* [The Diceware Passphrase Home Page](https://theworld.com/~reinhold/diceware.html) and [Diceware's passphrase length recommendations](https://theworld.com/%7Ereinhold/dicewarefaq.html#howlong). (Note that Diceware is focused on passphrases that are frequently entered manually by the user, and thus should be "easy for you to remember" and "easy for you to type accurately"; an SMS I/E passphrase will not normally be frequently entered manually by the user, and those criteria are thus less important, particularly if a passphrase manager is used.)
* Signal Messenger currently uses "64-character recovery keys" for its [Signal Secure Backups](https://signal.org/blog/introducing-secure-backups/).

SMS I/E itself stores stored passphrases in encrypted form, using a key stored in the [Android Keystore](https://developer.android.com/privacy-and-security/keystore). This means that an attacker who has access to an app installation containing a stored passphrase will be able to use the app to decrypt any files to which he has access that are encrypted with that passphrase, but will not be able to easily extract the passphrase itself.

## Standalone decryption

A Python script for standalone decryption of SMS I/E encrypted files is available [here](tools/ssef-decrypt.py) and documented [here](tools/Tools.md#ssef-decryptpy); additionally, it is straightforward to write decryption code for any platform on which [libsodium](https://github.com/jedisct1/libsodium) is available using the implementataion details and file format specification documented below.

## Internals

### Authenticated encryption via the libsodium SecretStream API

SMS I/E uses [libsodium's SecretStream API](https://doc.libsodium.org/doc/secret-key_cryptography/secretstream), via the [lazysodium Android binding](https://github.com/terl/lazysodium-android), to perform streaming authenticated encryption and decryption. SecretStream uses the [XChaCha20-Poly1305 extended nonce variant](https://en.wikipedia.org/wiki/ChaCha20-Poly1305#XChaCha20-Poly1305_%E2%80%93_extended_nonce_variant) of the [ChaCha20-Poly1305](https://en.wikipedia.org/wiki/ChaCha20-Poly1305) algorithms for encryption and decryption / authentication. SecretStream encrypts and decrypts data in ordered chunks, of arbitrary and variable size; SMS I/E uses SecretStream with fixed size chunks (with the possible exception of the final chunk). The default chunk size is 65536 bytes; this can be changed in the debug settings, and the default is subject to change in the future, but any changes will not affect the decryption of previously encrypted files, since the app stores the chunk size used for encryption in the file header and uses the stored value for decryption.

### Key derivation via the libsodium implementation of the Argon2id algorithm

The encryption / decryption key is derived from a user-supplied passphrase via the [libsodium implementation](https://libsodium.gitbook.io/doc/password_hashing#argon2) of the [Argon2id](https://en.wikipedia.org/wiki/Argon2) algorithm. For the Argon2id parameters, the app currently uses `memlimit == 65536` and `opslimit == 3` (the "SECOND RECOMMENDED option" of [RFC 9106](https://datatracker.ietf.org/doc/html/rfc9106#name-parameter-choice), for situations where "much less memory is available.") ([Libsodium does not expose the paralellism parameter](https://github.com/jedisct1/libsodium/issues/488).) The parameters used are subject to change, but any changes will not affect the decryption of previously encrypted files, since the app stores the parameters used for encryption in the file header and uses the stored parameters for decryption. A new Argon2id salt is randomly generated for each encryption operation.

### File format

Encrypted files have the following format:

#### Header

SMS I/E initially writes a 36 byte header to the file:

| # of Bytes | Contents |
|-------|----------|
| 6 | Magic number (file format identifier): "SSEF" + 0x00 + 0xFF |
| 2 | Format version: 0x00 + 0x01 |
| 4 | Argon2id time cost in iterations (integer) |
| 4 | Argon2id memory cost in kibibytes (integer) |
| 16 | Argon2id salt |
| 4 | SecretStream chunk size (integer) |

(All integers are in big-endian format.)

#### Encrypted data

SMS I/E then writes the SecretStream encrypted data. This consists of a 24 byte SecretStream header, which is opaque to applications using SecretStream, followed by a series of chunks of encrypted data, each of which consists of a chunk of plaintext transformed into ciphertext, plus an additional 17 bytes added by the SecretStream algorithm. All chunks of plaintext / ciphertext (except possibly the last one) are the size specified in the file header.
