# pic-sure-common
Common Java code shared by PIC-SURE applications

## Pre-requisites

* Java 25
* Before contributing code, please set up our git hook:
  `cp code-formatting/pre-commit.sh .git/hooks/pre-commit`
    * To skip formatting on a block of code, wrap in `spotless:off`, `spotless:on` comments