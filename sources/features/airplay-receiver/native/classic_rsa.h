#ifndef CLASSIC_RSA_H
#define CLASSIC_RSA_H

unsigned char *classic_rsa_base64_dec(const char *input, int *outlen);
unsigned char *classic_rsa_decrypt_key(const unsigned char *input, int inlen, int *outlen);

#endif
