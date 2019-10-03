FROM mysql:5.7.24

COPY psama_fence_setup.sql /tmp/fence_db_setup.sql

EXPOSE 3306

ENTRYPOINT ["docker-entrypoint.sh"]
CMD ["mysqld","--init-file","/tmp/fence_db_setup.sql"]
