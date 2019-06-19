openssl rand -hex 16 > /opt/local/hpds/encryption_key
gpg --import gpg_pub_key.asc
gpg --list-keys
gpg --always-trust --batch --no-tty -e -r "$GPG_USER" /opt/local/hpds/encryption_key 
base64 encryption_key.gpg > encryption_key_base64.gpg
echo "-----BEGIN PGP MESSAGE-----" && cat encryption_key_base64.gpg && echo "-----END PGP MESSAGE-----" > /opt/local/hpds/encryption_key.gpg

