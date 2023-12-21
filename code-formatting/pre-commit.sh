#!/bin/sh -e
CWD=$(pwd)
cd $(git rev-parse --show-toplevel)
format_cmd=""

# skip if NO_VERIFY env var set
if [ "$NO_VERIFY" ]; then
    echo 'code formatting skipped' 1>&2
    exit 0
fi

# I'm not great at bash, so this is a bit ugly, but I'll explain each pipe
# 1. Get all staged files
# 2. Reduce to just .java files
# 3. Replace newlines with commas (this was really hard to do in sed)
# 4. Replace commas with $,^.*
# 5. Crop off the last 4 chars
# This results in foo.java$,^.*bar.java$,^.*baz.java$
# I then append ^.* to the beginning of that.
STAGED_JAVA_FILES_AS_REGEX=$(git diff --staged --name-only --diff-filter=ACMR | grep '.java$' | tr '\n' ',' | sed -e 's/,/$,^.*/g' | sed 's/.\{4\}$//')
FILES_TO_RESTAGE=$(git diff --staged --name-only --diff-filter=ACMR)
if [ -n "$STAGED_JAVA_FILES_AS_REGEX" ]; then
   echo "Found the following staged java files to format: $STAGED_JAVA_FILES_AS_REGEX"
   mvn spotless:apply -DspotlessFiles=^.*$STAGED_JAVA_FILES_AS_REGEX
   git add $FILES_TO_RESTAGE
fi

cd $CWD