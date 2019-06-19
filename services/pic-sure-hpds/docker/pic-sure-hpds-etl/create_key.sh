openssl rand -hex 16 > /opt/local/hpds/encryption_key
gpg --import /opt/local/hpds/gpg_pub_key.asc
gpg --list-keys
gpg --always-trust --batch --yes --no-tty -e -r "$GPG_USER" /opt/local/hpds/encryption_key 
base64 /opt/local/hpds/encryption_key.gpg > /opt/local/hpds/encryption_key.gpg_base64
echo "-----BEGIN PGP MESSAGE-----" > /opt/local/hpds/encryption_key.asc && cat /opt/local/hpds/encryption_key.gpg_base64 >> /opt/local/hpds/encryption_key.asc && echo "-----END PGP MESSAGE-----" >> /opt/local/hpds/encryption_key.asc
rm /opt/local/hpds/encryption_key.gpg /opt/local/hpds/encryption_key.gpg_base64
