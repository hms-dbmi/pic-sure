#!/bin/sh

config() {
	# Create directory for HPDS container
	mkdir -p /scratch/hpds_symlink/
}

build() {
	# Build HTTPD container, with settings and directory structure
	docker-compose build
}

up() {
	# Start up all containers
	docker-compose up -d
}

down() {
	docker-compose down
}

logs() {
	docker-compose logs $*
}

ps() {
	docker-compose ps
}

rebuild() {
	docker-compose down
	docker system prune --force --all --volumes
	docker-compose pull
	docker-compose build
	docker-compose up -d
}

$*
