# Dictionary Project

## Purpose

PIC-SURE is an application that allows users to build and export a dataset of phenotypic and genomic data. Building that
dataset involves building a query that sets a series of constraints on variables present in that PIC-SURE. Large
PIC-SURE servers can have tens of thousands of variables, making the query building process complex.
Users need a mechanism to find the variables they want to query on. Currently, different implementations of PIC-SURE
have developed competing solutions to this problem. None of these solutions fully meet PIC-SURE's variable refinement
needs, and all the current solutions are fraught with architectural shortcomings, necessitating the need for a full
replacement.
The Dictionary Project's goal is to create a unified process for enabling the filtration of variables and the creation
of a query. It needs to work across our current projects, and, in anticipation of our push to productize PIC-SURE, it
needs to be workable for new PIC-SURE installations.

## Contribution

### Requirements

- Java 21
- Maven

### Setup

1. Copy the linting commit hook:  
`cp code-formatting/pre-commit.sh .git/hooks/pre-commit`
2. Do a clean build of the project:  
`mvn clean install`

## Usage

### Create Env File

```shell
cp env.example .env
vi .env
```

### Start DB

```shell
docker compose up -d --build dictionary-db
```

Once up, do some sanity checks in the database:

```shell
docker exec -ti dictionary-db psql -U picsure dictionary
```

### Start API

```shell
docker compose up -d --build
```
