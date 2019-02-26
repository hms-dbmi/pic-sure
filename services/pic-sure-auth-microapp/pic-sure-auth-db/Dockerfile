FROM mysql:5.7.24

RUN mkdir /tempsql/
COPY db/picsure-auth-dump-initial.sql /tempsql/picsure-auth-dump-initial.sql

EXPOSE 3306

ENTRYPOINT ["docker-entrypoint.sh"]
CMD ["mysqld","--init-file","/tempsql/picsure-auth-dump-initial.sql"]