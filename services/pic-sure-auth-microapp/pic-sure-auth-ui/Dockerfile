FROM httpd:2.4.27-alpine

RUN apk add --update openssl sed

# Replace virtual host config file with ours
COPY ./httpd_conf/httpd-vhosts.conf ${HTTPD_PREFIX}/conf/extra/httpd-vhosts.conf

# Enable virtual hosting config file
RUN sed -i '/^#Include conf.extra.httpd-vhosts.conf/s/^#//' ${HTTPD_PREFIX}/conf/httpd.conf

# Enable necessary proxy modules
RUN sed -i '/^#LoadModule proxy_module/s/^#//' ${HTTPD_PREFIX}/conf/httpd.conf
RUN sed -i  '/^#LoadModule proxy_http_module/s/^#//' ${HTTPD_PREFIX}/conf/httpd.conf
RUN sed -i '/^#LoadModule proxy_connect_module/s/^#//' ${HTTPD_PREFIX}/conf/httpd.conf

#### SSL ####
# enable ssl
RUN sed -i '/^#LoadModule ssl_module modules\/mod_ssl.so/s/^#//' ${HTTPD_PREFIX}/conf/httpd.conf
RUN sed -i '/^#LoadModule rewrite_module modules\/mod_rewrite.so/s/^#//' ${HTTPD_PREFIX}/conf/httpd.conf
RUN sed -i '/^#LoadModule socache_shmcb_module modules\/mod_socache_shmcb.so/s/^#//' ${HTTPD_PREFIX}/conf/httpd.conf
RUN mkdir /usr/local/apache2/logs/ssl_mutex

COPY ./httpd_conf/cert/server.key ${HTTPD_PREFIX}/cert/
COPY ./httpd_conf/cert/server.chain.crt ${HTTPD_PREFIX}/cert/
COPY ./httpd_conf/cert/server.crt ${HTTPD_PREFIX}/cert/
COPY ./httpd_conf/cert/server.chain ${HTTPD_PREFIX}/cert/

COPY ./src/main/webapp/index.html                   ${HTTPD_PREFIX}/htdocs/

COPY ./src/main/webapp/psamaui                      ${HTTPD_PREFIX}/htdocs/psamaui
COPY ./target/webjars/META-INF/resources/webjars    ${HTTPD_PREFIX}/htdocs/psamaui/webjars

# If testing FENCE login settings, you will need to uncomment the below commands
# But note that this is only for local testing. In other environments, the docker-compose.yml file
# will build a new settings.json and will owerwrite the one that is in the image, that this Dockerfile builds
COPY ./src/main/webapp/psamaui/settings/fence_settings.json ${HTTPD_PREFIX}/htdocs/psamaui/settings/settings.json