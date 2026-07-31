#! /usr/bin/env python3

import sys
import nacl.pwhash
import nacl.bindings.crypto_secretstream
import nacl.exceptions
from getpass import getpass

MAGIC_NUMBER = b'SSEF\x00\xff'
FORMAT_VERSION = b'\0\1'
SALT_LENGTH = 16
INTEGER_LENGTH = 4
HEADER_LENGTH = len(MAGIC_NUMBER) + len(FORMAT_VERSION) + (2 * INTEGER_LENGTH) + SALT_LENGTH
ENCRYPTION_KEY_LENGTH = 32
FILENAME_SUFFIX = '.ssef'
SCRIPT_NAME = 'ssef-decrypt.py'

if len(sys.argv) < 2 or len(sys.argv) > 3:
    sys.exit(f'Usage: {SCRIPT_NAME} <encrypted_file> [<decrypted_file>]')
if len(sys.argv) == 2 and not sys.argv[1].endswith(FILENAME_SUFFIX):
    sys.exit(f'Two argument form is only allowed with a filename ending in {FILENAME_SUFFIX}')
decrypted_filename = sys.argv[2] if len(sys.argv) == 3 else sys.argv[1].removesuffix(FILENAME_SUFFIX)
with open(sys.argv[1], 'rb') as encrypted_file:
    with open(decrypted_filename, 'wb') as decrypted_file:
        if encrypted_file.read(len(MAGIC_NUMBER)) != MAGIC_NUMBER:
            sys.exit(f'\'{sys.argv[1]}\' is not an SSEF encrypted file')
        encrypted_file.seek(len(FORMAT_VERSION), 1)  # we don't currently do anything with the FORMAT_VERSION
        t_cost_in_iterations = int.from_bytes(encrypted_file.read(INTEGER_LENGTH))
        m_cost_in_kibibytes = int.from_bytes(encrypted_file.read(INTEGER_LENGTH))
        salt = encrypted_file.read(SALT_LENGTH)
        passphrase = getpass("Enter passphrase: ").encode()
        try:
            decryption_key = nacl.pwhash.argon2id.kdf(ENCRYPTION_KEY_LENGTH, passphrase, salt, t_cost_in_iterations,
                                                      m_cost_in_kibibytes)
        except nacl.exceptions.RuntimeError:
            sys.exit('Key derivation failure, probably due to memory allocation failure')
        encrypted_chunk_size = int.from_bytes(encrypted_file.read(
            INTEGER_LENGTH)) + nacl.bindings.crypto_secretstream.crypto_secretstream_xchacha20poly1305_ABYTES
        st = nacl.bindings.crypto_secretstream.crypto_secretstream_xchacha20poly1305_state()
        secret_stream_header = encrypted_file.read(
            nacl.bindings.crypto_secretstream.crypto_secretstream_xchacha20poly1305_HEADERBYTES)
        try:
            nacl.bindings.crypto_secretstream.crypto_secretstream_xchacha20poly1305_init_pull(st, secret_stream_header,
                                                                                              decryption_key)
        except nacl.exceptions.RuntimeError:
            sys.exit('Incomplete SecretStream header')
        chunk_number = 1
        while True:
            encrypted_chunk = encrypted_file.read(encrypted_chunk_size)
            try:
                decrypted_chunk, tag = nacl.bindings.crypto_secretstream.crypto_secretstream_xchacha20poly1305_pull(st,
                                                                                                                    encrypted_chunk)
            except nacl.exceptions.RuntimeError:
                sys.exit(f'Decryption failure on chunk {chunk_number}')
            if decrypted_chunk == b'':
                sys.exit('End of file reached before end of stream')
            decrypted_file.write(decrypted_chunk)
            if tag == nacl.bindings.crypto_secretstream.crypto_secretstream_xchacha20poly1305_TAG_FINAL:
                sys.exit(None if encrypted_file.read(1) == b'' else 'End of stream reached before end of file')
            chunk_number += 1
