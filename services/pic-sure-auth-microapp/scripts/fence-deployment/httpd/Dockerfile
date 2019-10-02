FROM dbmi/pic-sure-hpds-ui:master.0800720 AS picsureui
#FROM dbmi/pic-sure-auth-ui:fence-integration_e55da771c1c5 AS psamaui
FROM httpd:2.4.27-alpine

RUN apk add --update openssl sed curl jq python3
RUN pip3 install --upgrade pip && pip install PyJWT

# Replace virtual host config file with ours
COPY httpd-vhosts.conf ${HTTPD_PREFIX}/conf/extra/httpd-vhosts.conf

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

COPY ./cert/server.key ${HTTPD_PREFIX}/cert/
COPY ./cert/server.chain.crt ${HTTPD_PREFIX}/cert/
COPY ./cert/server.crt ${HTTPD_PREFIX}/cert/
COPY ./cert/server.chain ${HTTPD_PREFIX}/cert/

#COPY --from=psamaui /usr/local/apache2/htdocs ${HTTPD_PREFIX}/htdocs
COPY --from=picsureui /usr/local/apache2/htdocs ${HTTPD_PREFIX}/htdocs

COPY index.html ${HTTPD_PREFIX}/htdocs/
